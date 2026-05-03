package com.insightspark.service;

import com.insightspark.core.auth.AuthContext.UserPrincipal;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, CaptchaChallenge> captchas = new ConcurrentHashMap<>();
    private final Map<String, UserPrincipal> sessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void initAuthTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_user` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL UNIQUE,
                  `username` VARCHAR(64) NOT NULL UNIQUE,
                  `nickname` VARCHAR(64) NOT NULL,
                  `phone` VARCHAR(32) NULL UNIQUE,
                  `email` VARCHAR(128) NULL UNIQUE,
                  `password_hash` VARCHAR(512) NOT NULL,
                  `password_salt` VARCHAR(128) NOT NULL,
                  `password_algorithm` VARCHAR(64) NOT NULL DEFAULT 'PBKDF2WithHmacSHA256',
                  `role` VARCHAR(32) NOT NULL DEFAULT 'USER',
                  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                  `last_login_at` DATETIME NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_is_user_role` (`role`),
                  INDEX `idx_is_user_status` (`status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RBAC用户表';
                """);
        ensureUser("demo-user", "demo-user", "普通用户", null, "user@example.com", "user123456", "USER");
        ensureUser("admin", "admin", "管理员", null, "admin@example.com", "admin123456", "ADMIN");
    }

    public Map<String, Object> captcha() {
        int left = 10 + RANDOM.nextInt(40);
        int right = 1 + RANDOM.nextInt(20);
        String captchaId = UUID.randomUUID().toString();
        String answer = String.valueOf(left + right);
        captchas.put(captchaId, new CaptchaChallenge(answer, Instant.now().plusSeconds(180)));
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="132" height="44" viewBox="0 0 132 44">
                  <rect width="132" height="44" rx="8" fill="#eef4ff"/>
                  <path d="M8 32 C34 10, 58 46, 124 14" fill="none" stroke="#8fb5ff" stroke-width="2" opacity=".55"/>
                  <text x="66" y="28" text-anchor="middle" font-family="Arial, sans-serif" font-size="20" font-weight="700" fill="#1f3f77">%d + %d = ?</text>
                </svg>
                """.formatted(left, right);
        String image = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Map.of("captchaId", captchaId, "image", image, "expiresIn", 180);
    }

    public Map<String, Object> register(Map<String, Object> request) {
        validateCaptcha(request);
        String username = requireText(request, "username", "用户名不能为空");
        String password = requireText(request, "password", "密码不能为空");
        if (password.length() < 8) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
        String phone = blankToNull(Objects.toString(request.get("phone"), ""));
        String email = blankToNull(Objects.toString(request.get("email"), ""));
        if (phone == null && email == null) {
            throw new IllegalArgumentException("手机号和邮箱至少填写一个");
        }
        String role = Objects.toString(request.getOrDefault("role", "USER")).toUpperCase();
        if (!role.equals("USER") && !role.equals("ADMIN")) {
            role = "USER";
        }
        String userId = role.toLowerCase() + "-" + System.currentTimeMillis() + Math.abs(RANDOM.nextInt(999));
        String nickname = blankToNull(Objects.toString(request.get("nickname"), ""));
        String salt = newSalt();
        String hash = hashPassword(password, salt);
        jdbcTemplate.update("""
                INSERT INTO is_user(user_id, username, nickname, phone, email, password_hash, password_salt, role, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, userId, username, nickname == null ? username : nickname, phone, email, hash, salt, role);
        return login(Map.of("account", username, "password", password, "captchaId", request.get("captchaId"), "captchaCode", request.get("captchaCode")), false);
    }

    public Map<String, Object> login(Map<String, Object> request) {
        return login(request, true);
    }

    public UserPrincipal authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return sessions.get(token.replaceFirst("(?i)^Bearer\\s+", "").trim());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token.replaceFirst("(?i)^Bearer\\s+", "").trim());
        }
    }

    private Map<String, Object> login(Map<String, Object> request, boolean checkCaptcha) {
        if (checkCaptcha) {
            validateCaptcha(request);
        }
        String account = requireText(request, "account", "账号不能为空");
        String password = requireText(request, "password", "密码不能为空");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, user_id AS userId, username, nickname, phone, email, password_hash AS passwordHash,
                       password_salt AS passwordSalt, role, status, last_login_at AS lastLoginAt, created_at AS createdAt
                FROM is_user
                WHERE username = ? OR phone = ? OR email = ?
                LIMIT 1
                """, account, account, account);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        Map<String, Object> user = rows.get(0);
        if (!"ACTIVE".equals(Objects.toString(user.get("status")))) {
            throw new IllegalArgumentException("账号已被停用");
        }
        String expected = Objects.toString(user.get("passwordHash"), "");
        String actual = hashPassword(password, Objects.toString(user.get("passwordSalt"), ""));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        jdbcTemplate.update("UPDATE is_user SET last_login_at = NOW() WHERE id = ?", user.get("id"));
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        UserPrincipal principal = toPrincipal(user);
        sessions.put(token, principal);
        return Map.of("token", token, "user", publicUser(user));
    }

    private void validateCaptcha(Map<String, Object> request) {
        String captchaId = requireText(request, "captchaId", "请先获取验证码");
        String code = requireText(request, "captchaCode", "请输入验证码");
        CaptchaChallenge challenge = captchas.remove(captchaId);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now()) || !challenge.answer().equals(code.trim())) {
            throw new IllegalArgumentException("验证码不正确或已过期");
        }
    }

    private void ensureUser(String userId, String username, String nickname, String phone, String email, String password, String role) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            return;
        }
        String salt = newSalt();
        jdbcTemplate.update("""
                INSERT INTO is_user(user_id, username, nickname, phone, email, password_hash, password_salt, role, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, userId, username, nickname, phone, email, hashPassword(password, salt), salt, role);
    }

    private Map<String, Object> publicUser(Map<String, Object> user) {
        return Map.of(
                "id", user.get("id"),
                "userId", user.get("userId"),
                "username", user.get("username"),
                "nickname", user.get("nickname"),
                "phone", user.get("phone") == null ? "" : user.get("phone"),
                "email", user.get("email") == null ? "" : user.get("email"),
                "role", user.get("role"),
                "status", user.get("status")
        );
    }

    private UserPrincipal toPrincipal(Map<String, Object> user) {
        return new UserPrincipal(
                ((Number) user.get("id")).longValue(),
                Objects.toString(user.get("userId")),
                Objects.toString(user.get("username")),
                Objects.toString(user.get("nickname")),
                Objects.toString(user.get("role"))
        );
    }

    private String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), HASH_ITERATIONS, HASH_BITS);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    private String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String requireText(Map<String, Object> request, String key, String message) {
        String value = Objects.toString(request.get(key), "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String blankToNull(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? null : text;
    }

    private record CaptchaChallenge(String answer, Instant expiresAt) {
    }
}
