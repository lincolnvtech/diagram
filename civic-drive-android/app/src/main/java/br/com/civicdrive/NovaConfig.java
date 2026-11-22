package br.com.civicdrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Configuração local; credencial protegida por chave não exportável do Android Keystore. */
final class NovaConfig {
    private static final String KEY_ALIAS = "civic_drive_nova_v1";
    private static final String PREFS = "nova_config";
    private final SharedPreferences prefs;

    NovaConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String url() { return prefs.getString("endpoint", ""); }
    boolean hasToken() { return prefs.contains("token_cipher"); }

    static String validateHttpsEndpoint(String raw) {
        String endpoint = raw == null ? "" : raw.trim();
        try {
            URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() > 65535 ||
                uri.getHost().isEmpty()) {
                throw new IllegalArgumentException("Informe um endereço HTTPS completo e válido.");
            }
            return endpoint;
        } catch (Exception e) {
            throw new IllegalArgumentException("A Nova exige HTTPS válido. Ex.: https://seu-dominio/api/civic/chat");
        }
    }

    void save(String rawUrl, String newToken) throws Exception {
        String endpoint = validateHttpsEndpoint(rawUrl);
        boolean changingToken = newToken != null && !newToken.trim().isEmpty();
        if (!changingToken && !hasToken()) throw new IllegalArgumentException("Informe o token da ponte Nova.");
        String encrypted = changingToken ? encrypt(newToken.trim()) : null;
        SharedPreferences.Editor editor = prefs.edit().putString("endpoint", endpoint);
        if (encrypted != null) editor.putString("token_cipher", encrypted);
        if (!editor.commit()) throw new IllegalStateException("Não foi possível gravar a configuração.");
    }

    String token() throws Exception {
        String saved = prefs.getString("token_cipher", null);
        if (saved == null || saved.isEmpty()) throw new IllegalStateException("Token não configurado.");
        byte[] pack = Base64.decode(saved, Base64.NO_WRAP);
        if (pack.length < 13) throw new IllegalStateException("Token protegido inválido.");
        byte[] nonce = new byte[12];
        byte[] cipherText = new byte[pack.length - nonce.length];
        System.arraycopy(pack, 0, nonce, 0, nonce.length);
        System.arraycopy(pack, nonce.length, cipherText, 0, cipherText.length);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, nonce));
        return new String(aes.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    void clear() {
        prefs.edit().remove("endpoint").remove("token_cipher").apply();
    }

    private static String encrypt(String secret) throws Exception {
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] nonce = aes.getIV();
        byte[] encrypted = aes.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] pack = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, pack, 0, nonce.length);
        System.arraycopy(encrypted, 0, pack, nonce.length, encrypted.length);
        return Base64.encodeToString(pack, Base64.NO_WRAP);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return gen.generateKey();
    }
}
