package com.insightspark.c.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时读取 is_system_config；保存配置后自动失效缓存，供各业务模块注入使用。
 */
@Service
public class StackCRuntimeConfigProvider {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, String> cache = null;

    public void invalidateCache() {
        cache = null;
    }

    public String getString(String key, String defaultValue) {
        String v = snapshot().get(key);
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        String s = v.toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s);
    }

    public List<String> getStringList(String key) {
        String raw = getString(key, "[]");
        try {
            List<String> list = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> getJsonObjectList(String key) {
        String raw = getString(key, "[]");
        try {
            List<Map<String, Object>> list = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> snapshot() {
        Map<String, String> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT config_key AS configKey, config_value AS configValue
                    FROM is_system_config
                    """);
            Map<String, String> map = new ConcurrentHashMap<>();
            for (Map<String, Object> row : rows) {
                map.put(
                        Objects.toString(row.get("configKey"), ""),
                        Objects.toString(row.get("configValue"), ""));
            }
            cache = Collections.unmodifiableMap(map);
            return cache;
        }
    }
}
