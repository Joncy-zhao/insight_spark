package com.insightspark.core.security;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

public final class DatasourcePasswordEncryptor {

    private static final String PREFIX = "AES:";

    private DatasourcePasswordEncryptor() {
    }

    public static String encrypt(String password) {
        if (password == null || password.isBlank() || password.startsWith(PREFIX)) {
            return password;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            return PREFIX + Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("数据源密码加密失败", e);
        }
    }

    public static String decrypt(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isBlank() || !encryptedPassword.startsWith(PREFIX)) {
            return encryptedPassword;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key());
            byte[] decoded = Base64.getDecoder().decode(encryptedPassword.substring(PREFIX.length()));
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("数据源密码解密失败", e);
        }
    }

    private static SecretKeySpec key() throws Exception {
        String raw = System.getenv().getOrDefault("INSIGHT_DATASOURCE_AES_KEY", "insight-spark-default-aes-key");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
    }
}
