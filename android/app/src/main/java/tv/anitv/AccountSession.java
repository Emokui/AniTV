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
    private static final Object LOCK = new Object();
    private static final Object KEY_LOCK = new Object();
    private static final String KEY_ALIAS = "anitv-api-session";
    private final SharedPreferences prefs;
    AccountSession(Context context){prefs=context.getSharedPreferences("api-account",Context.MODE_PRIVATE);}
    private static String origin(HttpUrl url){return url.scheme()+"://"+url.host()+":"+url.port();}
    private boolean hasToken(HttpUrl url){return url.isHttps() && origin(url).equals(prefs.getString("origin","")) && prefs.contains("token");}
    boolean available(HttpUrl url){
        synchronized (LOCK) { return hasToken(url) && !prefs.getString("iv", "").isEmpty(); }
    }
    long revision(){return prefs.getLong("revision",0);}
    String cacheScope(HttpUrl endpoint) {
        long rev = revision();
        boolean avail = available(endpoint);
        return rev != 0 || avail ? rev + ":" + avail : "";
    }
    okhttp3.Request scopedRequest(HttpUrl url, HttpUrl endpoint) {
        String scope = cacheScope(endpoint);
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        if (!scope.isEmpty()) builder.tag(CatalogRepository.CacheScope.class, new CatalogRepository.CacheScope(scope));
        return builder.build();
    }
    void clear(){synchronized (LOCK) { prefs.edit().clear().putLong("revision",revision()+1).apply(); }}
    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }
    private static SecretKey key(KeyStore store, boolean create) throws Exception {
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    void save(HttpUrl url,String token) throws Exception {
        if(!url.isHttps())throw new IllegalArgumentException("登录需要 HTTPS");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] value;
        synchronized (KEY_LOCK) {
            KeyStore store = keyStore();
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key(store, true));
            } catch (android.security.keystore.KeyPermanentlyInvalidatedException | java.security.UnrecoverableKeyException invalid) {
                store.deleteEntry(KEY_ALIAS);
                cipher.init(Cipher.ENCRYPT_MODE, key(store, true));
            }
            value=cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        synchronized (LOCK) {
            if(!prefs.edit().putString("origin",origin(url)).putString("iv",android.util.Base64.encodeToString(cipher.getIV(),2))
                    .putString("token",android.util.Base64.encodeToString(value,2)).putLong("revision",revision()+1).commit())throw new java.io.IOException("无法保存登录状态");
        }
    }
    String header(HttpUrl url) throws java.io.IOException {
        final long savedRevision;
        final String iv, encrypted;
        synchronized (LOCK) {
            if (!hasToken(url)) return "";
            iv = prefs.getString("iv", "");
            encrypted = prefs.getString("token", "");
            savedRevision = revision();
            if (iv.isEmpty() || encrypted.isEmpty()) {
                clear();
                return "";
            }
        }
        final String token;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(keyStore(), false),new GCMParameterSpec(128,android.util.Base64.decode(iv,2)));
            token=new String(cipher.doFinal(android.util.Base64.decode(encrypted,2)),java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception invalid) {
            synchronized (LOCK) {
                if (revision() == savedRevision) clear();
            }
            return "";
        }
        if (revision() != savedRevision) return "";
        return AccountCodec.csv(AccountCodec.fields(token,Long.toHexString(System.currentTimeMillis()+60000)));
    }
}
