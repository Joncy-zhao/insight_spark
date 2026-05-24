package com.insightspark.service;

import com.insightspark.core.auth.AuthContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class VoicePreferenceService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initVoicePreferenceTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_voice_preference` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `user_id` VARCHAR(64) NOT NULL,
                  `recognition_locale` VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
                  `voice_locale` VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
                  `voice_gender` VARCHAR(16) NOT NULL DEFAULT 'female',
                  `speech_rate` DECIMAL(4,2) NOT NULL DEFAULT 1.00,
                  `speech_volume` DECIMAL(4,2) NOT NULL DEFAULT 0.85,
                  `auto_speak_conclusion` TINYINT(1) NOT NULL DEFAULT 0,
                  `auto_send_after_recognize` TINYINT(1) NOT NULL DEFAULT 0,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_voice_preference_user` (`user_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户语音偏好';
                """);
    }

    public Map<String, Object> getCurrentUserPreference() {
        String userId = currentUserId();
        Map<String, Object> row = jdbcTemplate.query("""
                SELECT recognition_locale AS recognitionLocale,
                       voice_locale AS voiceLocale,
                       voice_gender AS selectedVoiceGender,
                       speech_rate AS speechRate,
                       speech_volume AS speechVolume,
                       auto_speak_conclusion AS autoSpeakConclusion,
                       auto_send_after_recognize AS autoSendAfterRecognize
                  FROM is_voice_preference
                 WHERE user_id = ?
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recognitionLocale", rs.getString("recognitionLocale"));
            result.put("voiceLocale", rs.getString("voiceLocale"));
            result.put("selectedVoiceGender", rs.getString("selectedVoiceGender"));
            result.put("speechRate", rs.getDouble("speechRate"));
            result.put("speechVolume", rs.getDouble("speechVolume"));
            result.put("autoSpeakConclusion", rs.getInt("autoSpeakConclusion") == 1);
            result.put("autoSendAfterRecognize", rs.getInt("autoSendAfterRecognize") == 1);
            return result;
        }, userId);
        return row == null ? defaultPreference() : row;
    }

    public Map<String, Object> saveCurrentUserPreference(Map<String, Object> request) {
        String userId = currentUserId();
        Map<String, Object> normalized = normalizePreference(request);
        jdbcTemplate.update("""
                INSERT INTO is_voice_preference (
                    user_id,
                    recognition_locale,
                    voice_locale,
                    voice_gender,
                    speech_rate,
                    speech_volume,
                    auto_speak_conclusion,
                    auto_send_after_recognize
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    recognition_locale = VALUES(recognition_locale),
                    voice_locale = VALUES(voice_locale),
                    voice_gender = VALUES(voice_gender),
                    speech_rate = VALUES(speech_rate),
                    speech_volume = VALUES(speech_volume),
                    auto_speak_conclusion = VALUES(auto_speak_conclusion),
                    auto_send_after_recognize = VALUES(auto_send_after_recognize)
                """,
                userId,
                normalized.get("recognitionLocale"),
                normalized.get("voiceLocale"),
                normalized.get("selectedVoiceGender"),
                normalized.get("speechRate"),
                normalized.get("speechVolume"),
                Boolean.TRUE.equals(normalized.get("autoSpeakConclusion")) ? 1 : 0,
                Boolean.TRUE.equals(normalized.get("autoSendAfterRecognize")) ? 1 : 0
        );
        return getCurrentUserPreference();
    }

    private Map<String, Object> normalizePreference(Map<String, Object> request) {
        Map<String, Object> defaults = defaultPreference();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recognitionLocale", normalizeLocale(request.get("recognitionLocale"), Objects.toString(defaults.get("recognitionLocale"))));
        result.put("voiceLocale", normalizeLocale(request.get("voiceLocale"), Objects.toString(defaults.get("voiceLocale"))));
        result.put("selectedVoiceGender", normalizeGender(request.get("selectedVoiceGender")));
        result.put("speechRate", clampDouble(request.get("speechRate"), 0.6D, 1.4D, 1.0D));
        result.put("speechVolume", clampDouble(request.get("speechVolume"), 0D, 1D, 0.85D));
        result.put("autoSpeakConclusion", parseBoolean(request.get("autoSpeakConclusion")));
        result.put("autoSendAfterRecognize", parseBoolean(request.get("autoSendAfterRecognize")));
        return result;
    }

    private Map<String, Object> defaultPreference() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("recognitionLocale", "zh-CN");
        defaults.put("voiceLocale", "zh-CN");
        defaults.put("selectedVoiceGender", "female");
        defaults.put("speechRate", 1.0D);
        defaults.put("speechVolume", 0.85D);
        defaults.put("autoSpeakConclusion", false);
        defaults.put("autoSendAfterRecognize", false);
        return defaults;
    }

    private String currentUserId() {
        return AuthContext.userId();
    }

    private String normalizeLocale(Object value, String fallback) {
        String text = Objects.toString(value, fallback).trim();
        if (text.isBlank()) {
            return fallback;
        }
        return text;
    }

    private String normalizeGender(Object value) {
        String text = Objects.toString(value, "female").trim().toLowerCase();
        if ("male".equals(text) || text.contains("男")) {
            return "male";
        }
        return "female";
    }

    private Double clampDouble(Object value, double min, double max, double fallback) {
        double number = fallback;
        if (value instanceof Number numberValue) {
            number = numberValue.doubleValue();
        } else if (value != null) {
            try {
                number = Double.parseDouble(Objects.toString(value).trim());
            } catch (NumberFormatException ignored) {
                number = fallback;
            }
        }
        return Math.max(min, Math.min(max, number));
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = Objects.toString(value, "").trim().toLowerCase();
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "on".equals(text);
    }
}
