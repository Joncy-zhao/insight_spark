package com.insightspark.core.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class DatasourcePasswordEncryptor {

    private static final String PREFIX = "ENC:";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private DatasourcePasswordEncryptor() {
    }

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank() || plainText.startsWith(PREFIX)) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Datasource password encryption failed", e);
        }
    }

    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank() || !cipherText.startsWith(PREFIX)) {
            return cipherText;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Datasource password decryption failed", e);
        }
    }

    private static SecretKeySpec key() throws Exception {
        String seed = System.getenv().getOrDefault("DATASOURCE_AES_KEY", "insight-spark-dev-datasource-key");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
