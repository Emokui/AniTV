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
    private final SharedPreferences prefs;
    AccountSession(Context context){prefs=context.getSharedPreferences("api-account",Context.MODE_PRIVATE);}
    private static String origin(HttpUrl url){return url.scheme()+"://"+url.host()+":"+url.port();}
    boolean available(HttpUrl url){return url.isHttps() && origin(url).equals(prefs.getString("origin","")) && prefs.contains("token");}
    long revision(){return prefs.getLong("revision",0);}
    void clear(){long next=revision()+1;prefs.edit().clear().putLong("revision",next).apply();}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias="anitv-api-session";
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    void save(HttpUrl url,String token) throws Exception {
        if(!url.isHttps())throw new IllegalArgumentException("登录需要 HTTPS");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] value=cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!prefs.edit().putString("origin",origin(url)).putString("iv",android.util.Base64.encodeToString(cipher.getIV(),2))
                .putString("token",android.util.Base64.encodeToString(value,2)).putLong("revision",revision()+1).commit())throw new java.io.IOException("无法保存登录状态");
    }
    String header(HttpUrl url) throws java.io.IOException {
        if(!available(url))return "";
        try {Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,android.util.Base64.decode(prefs.getString("iv",""),2)));
            String token=new String(cipher.doFinal(android.util.Base64.decode(prefs.getString("token",""),2)),java.nio.charset.StandardCharsets.UTF_8);
            return AccountCodec.csv(AccountCodec.fields(token,Long.toHexString(System.currentTimeMillis()+60000)));
        }catch(Exception e){throw new java.io.IOException("登录状态失效，请重新登录");}
    }
}
