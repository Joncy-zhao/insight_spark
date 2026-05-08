package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class StackCSystemConfigService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listAll() {
        return jdbcTemplate.queryForList("""
                SELECT id, config_key AS configKey, config_value AS configValue, value_type AS valueType,
                       category, description, updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
                FROM is_system_config
                ORDER BY category, config_key
                """);
    }

    public void upsert(Map<String, Object> body) {
        String key = requireText(body, "configKey");
        String value = body.get("configValue") == null ? null : String.valueOf(body.get("configValue"));
        String valueType = Objects.toString(body.getOrDefault("valueType", "STRING"));
        String category = body.get("category") == null ? null : String.valueOf(body.get("category"));
        String description = body.get("description") == null ? null : String.valueOf(body.get("description"));
        String uid = AuthContext.userId();
        jdbcTemplate.update("""
                INSERT INTO is_system_config(config_key, config_value, value_type, category, description, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  config_value = VALUES(config_value),
                  value_type = VALUES(value_type),
                  category = VALUES(category),
                  description = VALUES(description),
                  updated_by = VALUES(updated_by),
                  updated_at = CURRENT_TIMESTAMP
                """, key, value, valueType, category, description, uid);
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }
}
