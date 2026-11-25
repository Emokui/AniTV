package tv.anitv;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.HttpUrl;

final class AccountSession {
    static final String LOGIN_REQUIRED = "接口需要有效登录，请到设置 → 账号管理登录或重新登录";
    static boolean requiresLogin(String message) {
        return message != null && (LOGIN_REQUIRED.equals(message) || "401".equals(message) ||
                message.startsWith("HTTP 401") || message.equals("错误码 401") || message.startsWith("错误码 401 · ") ||
                message.toLowerCase(java.util.Locale.ROOT).contains("unauthorized"));
    }
    static String userFacingError(String message) {
        return requiresLogin(message) ? LOGIN_REQUIRED : message;
    }
    interface KeyAccess {
        SecretKey key(boolean create) throws java.security.GeneralSecurityException, java.io.IOException;
        void reset() throws java.security.GeneralSecurityException, java.io.IOException;
    }
    static final class Snapshot {
        final long revision;
        final boolean available;
        Snapshot(long revision, boolean available) {
            this.revision = revision;
            this.available = available;
        }
        String cacheScope() {
            return revision != 0 || available ? revision + ":" + available : "";
        }
    }
    private static final Object LOCK = new Object();
    private static final Object KEY_LOCK = new Object();
    private static final String KEY_ALIAS = "anitv-api-session";
    private static final KeyAccess KEYSTORE_ACCESS = new KeyAccess() {
        @Override public SecretKey key(boolean create) throws java.security.GeneralSecurityException, java.io.IOException {
            return AccountSession.key(keyStore(), create);
        }
        @Override public void reset() throws java.security.GeneralSecurityException, java.io.IOException {
            keyStore().deleteEntry(KEY_ALIAS);
        }
    };
    private static KeyAccess cachedKeys;
    private static String cachedOrigin = "", cachedIv = "", cachedEncrypted = "", cachedToken;
    private static long cachedRevision = -1;
    private final SharedPreferences prefs;
    private final KeyAccess keys;
    AccountSession(Context context) { this(context, KEYSTORE_ACCESS); }
    AccountSession(Context context, KeyAccess keys) {
        prefs = context.getSharedPreferences("api-account", Context.MODE_PRIVATE);
        this.keys = keys;
    }
    private static String origin(HttpUrl url){return url.scheme()+"://"+url.host()+":"+url.port();}
    private boolean hasToken(HttpUrl url){return url.isHttps() && origin(url).equals(prefs.getString("origin","")) && prefs.contains("token");}
    boolean available(HttpUrl url){
        return snapshot(url).available;
    }
    long revision(){
        synchronized (LOCK) { return prefs.getLong("revision", 0); }
    }
    Snapshot snapshot(HttpUrl endpoint) {
        synchronized (LOCK) {
            long rev = prefs.getLong("revision", 0);
            boolean avail = hasToken(endpoint) && !prefs.getString("iv", "").isEmpty();
            return new Snapshot(rev, avail);
        }
    }
    String cacheScope(HttpUrl endpoint) {
        return snapshot(endpoint).cacheScope();
    }
    okhttp3.Request scopedRequest(HttpUrl url, HttpUrl endpoint) {
        String scope = cacheScope(endpoint);
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        if (!scope.isEmpty()) builder.tag(CatalogRepository.CacheScope.class, new CatalogRepository.CacheScope(scope));
        return builder.build();
    }
    private static void invalidateCacheLocked() {
        cachedKeys = null;
        cachedOrigin = "";
        cachedRevision = -1;
        cachedIv = "";
        cachedEncrypted = "";
        cachedToken = null;
    }
    private void clearLocked(long currentRevision) {
        invalidateCacheLocked();
        prefs.edit().clear().putLong("revision", currentRevision + 1).apply();
    }
    private void clearIfUnchanged(long expectedRevision) {
        synchronized (LOCK) {
            if (prefs.getLong("revision", 0) == expectedRevision) clearLocked(expectedRevision);
        }
    }
    void clear(){
        synchronized (LOCK) { clearLocked(prefs.getLong("revision", 0)); }
    }
    private static KeyStore keyStore() throws java.security.GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }
    private static SecretKey key(KeyStore store, boolean create) throws java.security.GeneralSecurityException {
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    void save(HttpUrl url,String token) throws Exception {
        if(!url.isHttps())throw new IllegalArgumentException("登录需要 HTTPS");
        final byte[] value, ivBytes;
        synchronized (KEY_LOCK) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.ENCRYPT_MODE, keys.key(true));
            } catch (java.security.UnrecoverableEntryException | java.security.InvalidKeyException invalid) {
                keys.reset();
                cipher.init(Cipher.ENCRYPT_MODE, keys.key(true));
            }
            value = cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ivBytes = cipher.getIV();
        }
        String savedOrigin = origin(url);
        String encodedIv = android.util.Base64.encodeToString(ivBytes, android.util.Base64.NO_WRAP);
        String encodedToken = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP);
        synchronized (LOCK) {
            long nextRevision = prefs.getLong("revision", 0) + 1;
            if(!prefs.edit().putString("origin",savedOrigin).putString("iv",encodedIv)
                    .putString("token",encodedToken).putLong("revision",nextRevision).commit())throw new java.io.IOException("无法保存登录状态");
            cachedKeys = keys;
            cachedOrigin = savedOrigin;
            cachedRevision = nextRevision;
            cachedIv = encodedIv;
            cachedEncrypted = encodedToken;
            cachedToken = token;
        }
    }
    String header(HttpUrl url) throws java.io.IOException {
        final long savedRevision;
        final String reqOrigin, iv, encrypted;
        String token = null;
        synchronized (LOCK) {
            if (!hasToken(url)) return "";
            reqOrigin = origin(url);
            iv = prefs.getString("iv", "");
            encrypted = prefs.getString("token", "");
            savedRevision = prefs.getLong("revision", 0);
            if (iv.isEmpty() || encrypted.isEmpty()) {
                clearLocked(savedRevision);
                return "";
            }
            if (cachedToken != null && cachedKeys == keys && cachedRevision == savedRevision
                    && reqOrigin.equals(cachedOrigin) && iv.equals(cachedIv) && encrypted.equals(cachedEncrypted)) {
                token = cachedToken;
            }
        }
        if (token == null) {
            final byte[] ivBytes, cipherBytes;
            final GCMParameterSpec spec;
            try {
                ivBytes = android.util.Base64.decode(iv, android.util.Base64.NO_WRAP);
                cipherBytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
                if (ivBytes.length == 0 || cipherBytes.length < 16) throw new IllegalArgumentException("Invalid session ciphertext");
                spec = new GCMParameterSpec(128, ivBytes);
            } catch (IllegalArgumentException invalid) {
                clearIfUnchanged(savedRevision);
                return "";
            }
            boolean missingKey = false;
            try {
                synchronized (KEY_LOCK) {
                    SecretKey secretKey = keys.key(false);
                    if (secretKey == null) {
                        missingKey = true;
                    } else {
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                        token = new String(cipher.doFinal(cipherBytes), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            } catch (java.security.UnrecoverableEntryException
                    | java.security.InvalidKeyException
                    | java.security.InvalidAlgorithmParameterException
                    | javax.crypto.BadPaddingException
                    | javax.crypto.IllegalBlockSizeException corrupted) {
                clearIfUnchanged(savedRevision);
                return "";
            } catch (java.security.GeneralSecurityException | java.security.ProviderException | java.io.IOException keystoreUnavailable) {
                android.util.Log.w("AniTVAccount", "session keystore unavailable");
                return "";
            }
            if (missingKey) {
                clearIfUnchanged(savedRevision);
                return "";
            }
            synchronized (LOCK) {
                if (prefs.getLong("revision", 0) != savedRevision || !reqOrigin.equals(prefs.getString("origin", ""))) {
                    return "";
                }
                cachedKeys = keys;
                cachedOrigin = reqOrigin;
                cachedRevision = savedRevision;
                cachedIv = iv;
                cachedEncrypted = encrypted;
                cachedToken = token;
            }
        }
        return AccountCodec.csv(AccountCodec.fields(token,Long.toHexString(System.currentTimeMillis()+60000)));
    }
}
