package com.insightspark.c.service;

import com.insightspark.core.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class StackCSystemConfigService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StackCSystemConfigBridge configBridge;

    @Autowired
    private StackCRuntimeConfigProvider runtimeConfigProvider;

    public List<Map<String, Object>> listAll() {
        ensureDefaults();
        return jdbcTemplate.queryForList("""
                SELECT id, config_key AS configKey, config_value AS configValue, value_type AS valueType,
                       category, description, updated_by AS updatedBy, created_at AS createdAt, updated_at AS updatedAt
                FROM is_system_config
                ORDER BY category, config_key
                """);
    }

    public Map<String, Object> listGroupedSchema() {
        ensureDefaults();
        List<Map<String, Object>> rows = listAll();
        Map<String, String> storedValues = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rowByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = Objects.toString(row.get("configKey"), "");
            storedValues.put(key, Objects.toString(row.get("configValue"), ""));
            rowByKey.put(key, row);
        }

        Map<String, StackCSystemConfigBridge.HydratedField> hydrated = configBridge.hydrateAll(storedValues);

        List<Map<String, Object>> modules = new ArrayList<>();
        int wiredCount = 0;
        int readOnlyCount = 0;
        for (String moduleId : StackCSystemConfigDefinitions.MODULE_ORDER) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (StackCSystemConfigDefinitions.ConfigDef def : StackCSystemConfigDefinitions.ALL) {
                if (!moduleId.equals(def.category())) {
                    continue;
                }
                StackCSystemConfigBridge.HydratedField hf = hydrated.get(def.key());
                String current = hf == null ? storedValues.get(def.key()) : hf.value();
                Map<String, Object> row = rowByKey.get(def.key());
                Map<String, Object> item = StackCSystemConfigDefinitions.toSchemaItem(def, current, row);
                if (hf != null && hf.meta() != null) {
                    item.put("binding", hf.meta().binding());
                    item.put("bindingSource", hf.meta().source());
                    item.put("bindingNote", hf.meta().note());
                    item.put("readOnly", hf.meta().readOnly());
                    if (!"STORE_ONLY".equals(hf.meta().binding()) && !"SYSTEM_CONFIG".equals(hf.meta().binding())) {
                        wiredCount++;
                    }
                    if (hf.meta().readOnly()) {
                        readOnlyCount++;
                    }
                }
                items.add(item);
            }
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("id", moduleId);
            module.put("title", StackCSystemConfigDefinitions.MODULE_TITLES.getOrDefault(moduleId, moduleId));
            module.put("items", items);
            module.put("itemCount", items.size());
            modules.add(module);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modules", modules);
        result.put("totalKeys", StackCSystemConfigDefinitions.ALL.size());
        result.put("wiredCount", wiredCount);
        result.put("readOnlyCount", readOnlyCount);
        result.put("moduleOrder", StackCSystemConfigDefinitions.MODULE_ORDER);
        return result;
    }

    public void ensureDefaults() {
        for (StackCSystemConfigDefinitions.ConfigDef def : StackCSystemConfigDefinitions.ALL) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO is_system_config(config_key, config_value, value_type, category, description)
                    VALUES (?, ?, ?, ?, ?)
                    """, def.key(), def.defaultValue(), def.valueType(), def.category(), def.description());
        }
    }

    public void upsert(Map<String, Object> body) {
        String key = requireText(body, "configKey");
        String value = body.get("configValue") == null ? null : String.valueOf(body.get("configValue"));
        String valueType = Objects.toString(body.getOrDefault("valueType", "STRING"));
        String category = body.get("category") == null ? null : String.valueOf(body.get("category"));
        String description = body.get("description") == null ? null : String.valueOf(body.get("description"));
        doUpsert(key, value, valueType, category, description);
        configBridge.apply(key, value);
    }

    public void batchUpsert(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("配置列表不能为空");
        }
        for (Map<String, Object> item : items) {
            upsert(item);
        }
    }

    public void resetModule(String moduleId) {
        String id = Objects.toString(moduleId, "").trim().toUpperCase();
        if (!StackCSystemConfigDefinitions.MODULE_TITLES.containsKey(id)) {
            throw new IllegalArgumentException("未知配置模块：" + moduleId);
        }
        for (StackCSystemConfigDefinitions.ConfigDef def : StackCSystemConfigDefinitions.ALL) {
            if (!id.equals(def.category())) {
                continue;
            }
            doUpsert(def.key(), def.defaultValue(), def.valueType(), def.category(), def.description());
            configBridge.apply(def.key(), def.defaultValue());
        }
    }

    private void doUpsert(String key, String value, String valueType, String category, String description) {
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
        runtimeConfigProvider.invalidateCache();
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项：" + key);
        }
        return String.valueOf(v).trim();
    }
}
