package com.rarisol.imagelingo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String ALIAS = "imagelingo-secret-key-v1";
    private static final String PREFS = "encrypted_secrets";
    private final SharedPreferences prefs;
    private final Map<String, String> decryptedCache = new HashMap<>();
    private SecretKey cachedKey;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void put(String name, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name).apply();
            decryptedCache.put(name, "");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        packed.putInt(iv.length).put(iv).put(encrypted);
        prefs.edit().putString(name, Base64.encodeToString(packed.array(), Base64.NO_WRAP)).apply();
        // Avoid a KeyStore lookup and AES operation for every page in a folder translation.
        decryptedCache.put(name, value);
    }

    public synchronized String get(String name) {
        if (decryptedCache.containsKey(name)) return decryptedCache.get(name);
        String packedText = prefs.getString(name, "");
        if (packedText == null || packedText.isEmpty()) {
            decryptedCache.put(name, "");
            return "";
        }
        try {
            byte[] packedBytes = Base64.decode(packedText, Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 32 || packed.remaining() <= ivLength) {
                discardUnreadable(name);
                return "";
            }
            byte[] iv = new byte[ivLength];
            packed.get(iv);
            byte[] encrypted = new byte[packed.remaining()];
            packed.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            String value = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            decryptedCache.put(name, value);
            return value;
        } catch (Exception ignored) {
            discardUnreadable(name);
            return "";
        }
    }

    /** Removes all encrypted credentials while retaining the install-scoped AES key. */
    public synchronized void clearAll() {
        prefs.edit().clear().apply();
        decryptedCache.clear();
    }

    private void discardUnreadable(String name) {
        prefs.edit().remove(name).apply();
        decryptedCache.put(name, "");
    }

    private SecretKey getOrCreateKey() throws Exception {
        if (cachedKey != null) return cachedKey;
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            cachedKey = ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
            return cachedKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        cachedKey = generator.generateKey();
        return cachedKey;
    }
}
