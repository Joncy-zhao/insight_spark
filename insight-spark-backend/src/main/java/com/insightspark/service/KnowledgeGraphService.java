package com.insightspark.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class KnowledgeGraphService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${insight.neo4j.enabled:true}")
    private boolean neo4jEnabled;

    @Value("${insight.neo4j.http-url:http://localhost:7474/db/neo4j/tx/commit}")
    private String neo4jHttpUrl;

    @Value("${insight.neo4j.username:neo4j}")
    private String neo4jUsername;

    @Value("${insight.neo4j.password:neo4j}")
    private String neo4jPassword;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void initKnowledgeGraphTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_kg_node` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `node_key` VARCHAR(255) NOT NULL UNIQUE,
                  `node_type` VARCHAR(64) NOT NULL,
                  `label` VARCHAR(255) NOT NULL,
                  `source_type` VARCHAR(64) NULL,
                  `source_id` VARCHAR(255) NULL,
                  `content` TEXT NULL,
                  `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX `idx_kg_node_type` (`node_type`),
                  INDEX `idx_kg_node_label` (`label`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量知识图谱节点';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_kg_edge` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `from_key` VARCHAR(255) NOT NULL,
                  `to_key` VARCHAR(255) NOT NULL,
                  `relation_type` VARCHAR(64) NOT NULL,
                  `weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_kg_edge` (`from_key`, `to_key`, `relation_type`),
                  INDEX `idx_kg_edge_from` (`from_key`),
                  INDEX `idx_kg_edge_to` (`to_key`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量知识图谱关系';
                """);
        addColumnIfMissing("is_kg_node", "node_type", "`node_type` VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN'");
        addColumnIfMissing("is_kg_node", "label", "`label` VARCHAR(255) NOT NULL DEFAULT ''");
        addColumnIfMissing("is_kg_node", "source_type", "`source_type` VARCHAR(64) NULL");
        addColumnIfMissing("is_kg_node", "source_id", "`source_id` VARCHAR(255) NULL");
        addColumnIfMissing("is_kg_node", "content", "`content` TEXT NULL");
        addColumnIfMissing("is_kg_node", "weight", "`weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00");
        addColumnIfMissing("is_kg_node", "created_at", "`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        addColumnIfMissing("is_kg_node", "updated_at", "`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        makeColumnNullableIfExists("is_kg_node", "name", "`name` VARCHAR(255) NULL");
        try {
            jdbcTemplate.update("UPDATE is_kg_node SET label = COALESCE(NULLIF(label, ''), name, node_key) WHERE label IS NULL OR label = ''");
        } catch (DataAccessException ignored) {
            jdbcTemplate.update("UPDATE is_kg_node SET label = COALESCE(NULLIF(label, ''), node_key) WHERE label IS NULL OR label = ''");
        }
        addColumnIfMissing("is_kg_edge", "relation_type", "`relation_type` VARCHAR(64) NOT NULL DEFAULT 'RELATED'");
        addColumnIfMissing("is_kg_edge", "weight", "`weight` DECIMAL(10,2) NOT NULL DEFAULT 1.00");
    }

    public Map<String, Object> syncGraph() {
        int nodeCount = 0;
        int edgeCount = 0;

        List<Map<String, Object>> uploadTables = jdbcTemplate.queryForList("""
                SELECT table_name AS tableName, display_name AS displayName, source_name AS sourceName,
                       row_count AS rowCount, field_count AS fieldCount
                FROM is_data_table
                WHERE status = 'ACTIVE'
                """);
        for (Map<String, Object> table : uploadTables) {
            String tableName = Objects.toString(table.get("tableName"), "");
            String tableKey = "upload_table:" + tableName;
            nodeCount += upsertNode(tableKey, "UPLOAD_TABLE", Objects.toString(table.get("displayName"), tableName),
                    "UPLOAD", tableName, "用户上传数据表，行数：" + table.get("rowCount") + "，字段数：" + table.get("fieldCount"), 2.0);
            List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, display_name AS displayName, field_type AS fieldType,
                           field_comment AS fieldComment, sensitive
                    FROM is_data_field
                    WHERE table_name = ?
                    """, tableName);
            for (Map<String, Object> field : fields) {
                String columnName = Objects.toString(field.get("columnName"), "");
                String fieldKey = tableKey + ":field:" + columnName;
                nodeCount += upsertNode(fieldKey, "FIELD", Objects.toString(field.get("displayName"), columnName),
                        "UPLOAD", tableName + "." + columnName,
                        "字段类型：" + field.get("fieldType") + "；敏感：" + field.get("sensitive") + "；" + Objects.toString(field.get("fieldComment"), ""),
                        Boolean.TRUE.equals(field.get("sensitive")) ? 2.0 : 1.0);
                edgeCount += upsertEdge(tableKey, fieldKey, "HAS_FIELD", 1.0);
                if (isSensitive(field.get("sensitive"))) {
                    String sensitiveKey = "tag:sensitive";
                    nodeCount += upsertNode(sensitiveKey, "TAG", "敏感字段", "SYSTEM", "sensitive", "需要脱敏或审计关注的字段", 3.0);
                    edgeCount += upsertEdge(fieldKey, sensitiveKey, "MARKED_AS", 2.0);
                }
            }
        }

        List<Map<String, Object>> officialTables = jdbcTemplate.queryForList("""
                SELECT d.id AS datasourceId, d.name AS datasourceName, t.table_name AS tableName,
                       t.table_comment AS tableComment, t.table_rows AS tableRows
                FROM is_official_datasource d
                JOIN is_official_schema_table t ON t.datasource_id = d.id
                """);
        for (Map<String, Object> table : officialTables) {
            String datasourceId = Objects.toString(table.get("datasourceId"), "");
            String tableName = Objects.toString(table.get("tableName"), "");
            String dsKey = "datasource:" + datasourceId;
            String tableKey = "official_table:" + datasourceId + ":" + tableName;
            nodeCount += upsertNode(dsKey, "DATASOURCE", Objects.toString(table.get("datasourceName"), datasourceId),
                    "OFFICIAL", datasourceId, "企业官方数据源", 2.0);
            nodeCount += upsertNode(tableKey, "OFFICIAL_TABLE", tableName, "OFFICIAL", datasourceId + "." + tableName,
                    "官方表：" + Objects.toString(table.get("tableComment"), "") + "；估算行数：" + table.get("tableRows"), 2.0);
            edgeCount += upsertEdge(dsKey, tableKey, "HAS_TABLE", 1.0);
            List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, data_type AS dataType, column_comment AS columnComment,
                           business_name AS businessName, sensitive
                    FROM is_official_schema_field
                    WHERE datasource_id = ? AND table_name = ?
                    """, table.get("datasourceId"), tableName);
            for (Map<String, Object> field : fields) {
                String columnName = Objects.toString(field.get("columnName"), "");
                String label = Objects.toString(field.get("businessName"), "");
                if (label.isBlank()) {
                    label = Objects.toString(field.get("columnComment"), columnName);
                }
                String fieldKey = tableKey + ":field:" + columnName;
                nodeCount += upsertNode(fieldKey, "FIELD", label.isBlank() ? columnName : label,
                        "OFFICIAL", datasourceId + "." + tableName + "." + columnName,
                        "字段类型：" + field.get("dataType") + "；数据库注释：" + Objects.toString(field.get("columnComment"), "") + "；敏感：" + field.get("sensitive"),
                        isSensitive(field.get("sensitive")) ? 2.0 : 1.0);
                edgeCount += upsertEdge(tableKey, fieldKey, "HAS_FIELD", 1.0);
                if (isSensitive(field.get("sensitive"))) {
                    String sensitiveKey = "tag:sensitive";
                    nodeCount += upsertNode(sensitiveKey, "TAG", "敏感字段", "SYSTEM", "sensitive", "需要脱敏或审计关注的字段", 3.0);
                    edgeCount += upsertEdge(fieldKey, sensitiveKey, "MARKED_AS", 2.0);
                }
            }
        }

        List<Map<String, Object>> reports = jdbcTemplate.queryForList("""
                SELECT id, table_name AS tableName, title, summary, metric_field AS metricField
                FROM is_diagnosis_report
                ORDER BY created_at DESC
                LIMIT 100
                """);
        for (Map<String, Object> report : reports) {
            String reportKey = "diagnosis_report:" + report.get("id");
            String tableName = Objects.toString(report.get("tableName"), "");
            nodeCount += upsertNode(reportKey, "DIAGNOSIS_REPORT", Objects.toString(report.get("title"), "智能诊断报告"),
                    "DIAGNOSIS", Objects.toString(report.get("id"), ""), Objects.toString(report.get("summary"), ""), 2.5);
            edgeCount += upsertEdge(reportKey, "upload_table:" + tableName, "ANALYZES", 1.5);
        }

        return Map.of("nodeUpsertCount", nodeCount, "edgeUpsertCount", edgeCount);
    }

    public Map<String, Object> graph(int limit) {
        int safeLimit = Math.max(10, Math.min(limit, 300));
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList("""
                SELECT node_key AS nodeKey, node_type AS nodeType, label, source_type AS sourceType,
                       source_id AS sourceId, content, weight
                FROM is_kg_node
                ORDER BY weight DESC, updated_at DESC
                LIMIT """ + " " + safeLimit);
        List<Map<String, Object>> edges = jdbcTemplate.queryForList("""
                SELECT from_key AS fromKey, to_key AS toKey, relation_type AS relationType, weight
                FROM is_kg_edge
                WHERE from_key IN (SELECT node_key FROM is_kg_node ORDER BY weight DESC, updated_at DESC LIMIT ?)
                   OR to_key IN (SELECT node_key FROM is_kg_node ORDER BY weight DESC, updated_at DESC LIMIT ?)
                ORDER BY weight DESC
                LIMIT ?
                """, safeLimit, safeLimit, safeLimit * 2);
        return Map.of("nodes", nodes, "edges", edges);
    }

    public Map<String, Object> overview() {
        List<Map<String, Object>> nodeTypes = jdbcTemplate.queryForList("""
                SELECT node_type AS type, COUNT(*) AS count
                FROM is_kg_node
                GROUP BY node_type
                ORDER BY count DESC
                """);
        List<Map<String, Object>> edgeTypes = jdbcTemplate.queryForList("""
                SELECT relation_type AS type, COUNT(*) AS count
                FROM is_kg_edge
                GROUP BY relation_type
                ORDER BY count DESC
                """);
        Integer nodeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_kg_node", Integer.class);
        Integer edgeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_kg_edge", Integer.class);
        return Map.of(
                "nodeCount", nodeCount == null ? 0 : nodeCount,
                "edgeCount", edgeCount == null ? 0 : edgeCount,
                "nodeTypes", nodeTypes,
                "edgeTypes", edgeTypes
        );
    }

    public List<Map<String, Object>> search(String keyword, int limit) {
        String term = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        if (term.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT node_key AS nodeKey, node_type AS nodeType, label, source_type AS sourceType,
                           source_id AS sourceId, content, weight
                    FROM is_kg_node
                    ORDER BY weight DESC, updated_at DESC
                    LIMIT """ + " " + safeLimit);
        }
        String like = "%" + term + "%";
        return jdbcTemplate.queryForList("""
                SELECT node_key AS nodeKey, node_type AS nodeType, label, source_type AS sourceType,
                       source_id AS sourceId, content, weight
                FROM is_kg_node
                WHERE label LIKE ? OR content LIKE ? OR source_id LIKE ?
                ORDER BY weight DESC, updated_at DESC
                LIMIT """ + " " + safeLimit, like, like, like);
    }

    public Map<String, Object> searchBundle(String keyword, int limit) {
        List<Map<String, Object>> nodes = search(keyword, limit);
        List<String> nodeKeys = nodes.stream().map(item -> Objects.toString(item.get("nodeKey"))).toList();
        List<Map<String, Object>> edges = nodeKeys.isEmpty()
                ? List.of()
                : jdbcTemplate.queryForList("""
                        SELECT from_key AS fromKey, to_key AS toKey, relation_type AS relationType, weight
                        FROM is_kg_edge
                        WHERE from_key IN (%s) OR to_key IN (%s)
                        ORDER BY weight DESC
                        LIMIT 80
                        """.formatted(placeholders(nodeKeys.size()), placeholders(nodeKeys.size())),
                doubledArgs(nodeKeys));
        return Map.of("nodes", nodes, "edges", edges, "ragContext", nodes);
    }

    public Map<String, Object> multiHopSearch(String keyword, String tableName, int depth, int limit) {
        List<Map<String, Object>> seeds = new ArrayList<>();
        if (tableName != null && !tableName.isBlank()) {
            seeds.addAll(search(tableName, limit));
        }
        seeds.addAll(search(keyword, limit));
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (Map<String, Object> seed : seeds) {
            nodeMap.put(Objects.toString(seed.get("nodeKey")), seed);
        }
        List<String> frontier = new ArrayList<>(nodeMap.keySet());
        List<Map<String, Object>> allEdges = new ArrayList<>();
        int safeDepth = Math.max(1, Math.min(depth, 4));
        int safeLimit = Math.max(5, Math.min(limit, 80));
        for (int i = 0; i < safeDepth && !frontier.isEmpty(); i++) {
            List<Map<String, Object>> edges = jdbcTemplate.queryForList("""
                    SELECT from_key AS fromKey, to_key AS toKey, relation_type AS relationType, weight
                    FROM is_kg_edge
                    WHERE from_key IN (%s) OR to_key IN (%s)
                    ORDER BY weight DESC
                    LIMIT %d
                    """.formatted(placeholders(frontier.size()), placeholders(frontier.size()), safeLimit * 3),
                    doubledArgs(frontier));
            allEdges.addAll(edges);
            List<String> next = new ArrayList<>();
            for (Map<String, Object> edge : edges) {
                next.add(Objects.toString(edge.get("fromKey")));
                next.add(Objects.toString(edge.get("toKey")));
            }
            next.removeIf(nodeMap::containsKey);
            if (next.isEmpty()) {
                break;
            }
            List<Map<String, Object>> nextNodes = jdbcTemplate.queryForList("""
                    SELECT node_key AS nodeKey, node_type AS nodeType, label, source_type AS sourceType,
                           source_id AS sourceId, content, weight
                    FROM is_kg_node
                    WHERE node_key IN (%s)
                    ORDER BY weight DESC
                    LIMIT %d
                    """.formatted(placeholders(next.size()), safeLimit), next.toArray());
            frontier = new ArrayList<>();
            for (Map<String, Object> node : nextNodes) {
                String nodeKey = Objects.toString(node.get("nodeKey"));
                nodeMap.putIfAbsent(nodeKey, node);
                frontier.add(nodeKey);
            }
        }
        List<Map<String, Object>> context = nodeMap.values().stream()
                .sorted((a, b) -> Double.compare(parseWeight(b.get("weight")), parseWeight(a.get("weight"))))
                .limit(safeLimit)
                .toList();
        return Map.of("nodes", context, "edges", allEdges, "ragContext", context,
                "depth", safeDepth, "neo4jEnabled", neo4jEnabled);
    }

    public List<Map<String, Object>> retrieveContext(String question, String tableName) {
        List<Map<String, Object>> context = new ArrayList<>();
        if (tableName != null && !tableName.isBlank()) {
            context.addAll(search(tableName, 8));
        }
        if (question != null && !question.isBlank()) {
            for (String token : question.split("[\\s,，。；;：:]+")) {
                if (token.length() >= 2) {
                    context.addAll(search(token, 5));
                }
                if (context.size() >= 12) {
                    break;
                }
            }
        }
        Map<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        for (Map<String, Object> item : context) {
            dedup.putIfAbsent(Objects.toString(item.get("nodeKey")), item);
        }
        return dedup.values().stream().limit(12).toList();
    }

    public List<Map<String, Object>> retrieveMultiHopContext(String question, String tableName) {
        return (List<Map<String, Object>>) multiHopSearch(question, tableName, 3, 16).get("ragContext");
    }

    private int upsertNode(String nodeKey, String nodeType, String label, String sourceType, String sourceId, String content, double weight) {
        jdbcTemplate.update("""
                INSERT INTO is_kg_node(node_key, node_type, label, source_type, source_id, content, weight)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE node_type = VALUES(node_type), label = VALUES(label),
                                        source_type = VALUES(source_type), source_id = VALUES(source_id),
                                        content = VALUES(content), weight = VALUES(weight)
                """, nodeKey, nodeType, label, sourceType, sourceId, content, weight);
        syncNeo4j("""
                MERGE (n:InsightNode {nodeKey: $nodeKey})
                SET n.nodeType = $nodeType, n.label = $label, n.sourceType = $sourceType,
                    n.sourceId = $sourceId, n.content = $content, n.weight = $weight
                """, Map.of("nodeKey", nodeKey, "nodeType", nodeType, "label", label,
                "sourceType", Objects.toString(sourceType, ""), "sourceId", Objects.toString(sourceId, ""),
                "content", Objects.toString(content, ""), "weight", weight));
        return 1;
    }

    private int upsertEdge(String fromKey, String toKey, String relationType, double weight) {
        jdbcTemplate.update("""
                INSERT INTO is_kg_edge(from_key, to_key, relation_type, weight)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE weight = VALUES(weight)
                """, fromKey, toKey, relationType, weight);
        syncNeo4j("""
                MATCH (a:InsightNode {nodeKey: $fromKey})
                MATCH (b:InsightNode {nodeKey: $toKey})
                MERGE (a)-[r:RELATED {relationType: $relationType}]->(b)
                SET r.weight = $weight
                """, Map.of("fromKey", fromKey, "toKey", toKey, "relationType", relationType, "weight", weight));
        return 1;
    }

    private boolean isSensitive(Object value) {
        if (value == null) {
            return false;
        }
        String text = Objects.toString(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] doubledArgs(List<String> values) {
        List<Object> args = new ArrayList<>(values);
        args.addAll(values);
        return args.toArray();
    }

    private void syncNeo4j(String cypher, Map<String, Object> params) {
        if (!neo4jEnabled) {
            return;
        }
        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "statements", List.of(Map.of("statement", cypher, "parameters", params))
            ));
            String token = Base64.getEncoder().encodeToString((neo4jUsername + ":" + neo4jPassword).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(neo4jHttpUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Neo4j 是增强链路；同步失败不影响本地 MySQL 图谱与主业务演示。
        }
    }

    private double parseWeight(Object value) {
        try {
            return Double.parseDouble(Objects.toString(value, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            try {
                jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
            } catch (DataAccessException ignored) {
                // Test databases can report information_schema differently; duplicate-column ALTER is safe to ignore here.
            }
        }
    }

    private void makeColumnNullableIfExists(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN " + definition);
        }
    }
}
