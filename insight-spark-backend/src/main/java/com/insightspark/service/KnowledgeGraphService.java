package com.insightspark.service;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='杞婚噺鐭ヨ瘑鍥捐氨鑺傜偣';
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='杞婚噺鐭ヨ瘑鍥捐氨鍏崇郴';
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
        makeColumnNullableIfExists("is_kg_edge", "edge_type", "`edge_type` VARCHAR(64) NULL DEFAULT 'RELATED'");
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
                    "UPLOAD", tableName, "上传数据表，行数：" + table.get("rowCount") + "，字段数：" + table.get("fieldCount"), 2.0);
            List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, display_name AS displayName, field_type AS fieldType,
                           field_comment AS fieldComment, `sensitive`
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
                    "瀹樻柟琛細" + Objects.toString(table.get("tableComment"), "") + "锛涗及绠楄鏁帮細" + table.get("tableRows"), 2.0);
            edgeCount += upsertEdge(dsKey, tableKey, "HAS_TABLE", 1.0);
            List<Map<String, Object>> fields = jdbcTemplate.queryForList("""
                    SELECT column_name AS columnName, data_type AS dataType, column_comment AS columnComment,
                           business_name AS businessName, `sensitive`
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

    public boolean hasGraphData() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_kg_node", Integer.class);
        return count != null && count > 0;
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
        if (!neo4jEnabled) {
            throw new IllegalStateException("Neo4j 未启用，已禁止回退到本地 MySQL 知识图谱");
        }
        try {
            return neo4jMultiHopSearch(keyword, tableName, depth, limit);
        } catch (Exception e) {
            throw new IllegalStateException("Neo4j 查询失败：" + safeErrorMessage(e), e);
        }
    }

    public List<Map<String, Object>> retrieveContext(String question, String tableName) {
        return castMapList(multiHopSearch(question, tableName, 2, 12).getOrDefault("ragContext", List.of()));
        /*
        if (question != null && !question.isBlank()) {
            for (String token : question.split("[\\s,锛屻€傦紱;锛?]+")) {
                if (token.length() >= 2) {
                    context.addAll(search(token, 5));
                }
                if (context.size() >= 12) {
                    break;
                }
            }
        }
        */
    }

    public Map<String, Object> retrieveMultiHopContext(String question, String tableName) {
        Map<String, Object> bundle = multiHopSearch(question, tableName, 3, 16);
        return Map.of(
                "nodes", bundle.getOrDefault("nodes", List.of()),
                "edges", bundle.getOrDefault("edges", List.of()),
                "pathText", bundle.getOrDefault("pathText", ""),
                "ragContext", bundle.getOrDefault("ragContext", List.of()),
                "depth", bundle.getOrDefault("depth", 3),
                "neo4jEnabled", bundle.getOrDefault("neo4jEnabled", neo4jEnabled)
        );
    }

    private Map<String, Object> neo4jMultiHopSearch(String keyword, String tableName, int depth, int limit) throws Exception {
        String term = Objects.toString(keyword, "").trim();
        String table = Objects.toString(tableName, "").trim();
        int safeDepth = Math.max(1, Math.min(depth, 4));
        int safeLimit = Math.max(5, Math.min(limit, 80));
        String cypher = """
                MATCH (seed:InsightNode)
                WHERE ($tableName <> '' AND (
                         seed.nodeKey CONTAINS $tableName
                      OR seed.sourceId CONTAINS $tableName
                      OR seed.label CONTAINS $tableName
                    ))
                   OR ($keyword <> '' AND (
                         seed.nodeKey CONTAINS $keyword
                      OR seed.sourceId CONTAINS $keyword
                      OR seed.label CONTAINS $keyword
                      OR seed.content CONTAINS $keyword
                    ))
                WITH collect(DISTINCT seed)[0..$limit] AS seeds
                UNWIND seeds AS seed
                MATCH path = (seed)-[*0..3]-(n:InsightNode)
                WITH collect(DISTINCT n)[0..$limit] AS nodes, collect(path)[0..$pathLimit] AS paths
                UNWIND nodes AS node
                WITH collect(DISTINCT {
                  nodeKey: node.nodeKey,
                  nodeType: node.nodeType,
                  label: node.label,
                  sourceType: node.sourceType,
                  sourceId: node.sourceId,
                  content: node.content,
                  weight: node.weight
                }) AS nodeRows, paths
                UNWIND paths AS path
                UNWIND relationships(path) AS rel
                WITH nodeRows, collect(DISTINCT {
                  fromKey: startNode(rel).nodeKey,
                  toKey: endNode(rel).nodeKey,
                  relationType: coalesce(rel.relationType, type(rel)),
                  weight: rel.weight
                })[0..$edgeLimit] AS edgeRows
                RETURN {nodes: nodeRows, edges: edgeRows} AS row
                """;
        List<Map<String, Object>> rows = neo4jQueryRows(cypher, Map.of(
                "keyword", term,
                "tableName", table,
                "limit", safeLimit,
                "pathLimit", safeLimit * 2,
                "edgeLimit", safeLimit * 3
        ));
        Map<String, Object> row = rows.isEmpty() ? Map.of("nodes", List.of(), "edges", List.of()) : rows.get(0);
        List<Map<String, Object>> nodes = castMapList(row.getOrDefault("nodes", List.of()));
        List<Map<String, Object>> edges = castMapList(row.getOrDefault("edges", List.of()));
        return Map.of("nodes", nodes, "edges", edges, "ragContext", nodes,
                "pathText", buildPathText(nodes, edges),
                "depth", safeDepth, "neo4jEnabled", true);
    }

    private List<Map<String, Object>> neo4jQueryRows(String cypher, Map<String, Object> params) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "statements", List.of(Map.of("statement", cypher, "parameters", params))
        ));
        String token = Base64.getEncoder().encodeToString((neo4jUsername + ":" + neo4jPassword).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(neo4jHttpUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " - " + response.body());
        }
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> errors = castMapList(body.get("errors"));
        if (!errors.isEmpty()) {
            throw new IllegalStateException(formatNeo4jErrors(errors));
        }
        List<Map<String, Object>> results = castMapList(body.get("results"));
        if (results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> data = castMapList(results.get(0).get("data"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : data) {
            Object rowObj = item.get("row");
            if (rowObj instanceof List<?> rowList && !rowList.isEmpty() && rowList.get(0) instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    row.put(Objects.toString(entry.getKey()), entry.getValue());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private String formatNeo4jErrors(List<Map<String, Object>> errors) {
        return errors.stream()
                .map(error -> {
                    String code = Objects.toString(error.get("code"), "").trim();
                    String message = Objects.toString(error.get("message"), "").trim();
                    if (code.isBlank()) {
                        return message.isBlank() ? Objects.toString(error) : message;
                    }
                    return message.isBlank() ? code : code + " - " + message;
                })
                .filter(item -> !item.isBlank())
                .findFirst()
                .orElse("未知 Neo4j 错误");
    }

    private String safeErrorMessage(Exception e) {
        String message = e == null ? "" : Objects.toString(e.getMessage(), "").trim();
        return message.isBlank() && e != null ? e.getClass().getSimpleName() : message;
    }

    private List<Map<String, Object>> castMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        row.put(Objects.toString(entry.getKey()), entry.getValue());
                    }
                    result.add(row);
                }
            }
            return result;
        }
        return List.of();
    }

    private String buildPathText(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        if (nodes == null || nodes.isEmpty()) {
            return "暂无图谱路径，请先同步知识图谱。";
        }
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            nodeMap.put(Objects.toString(node.get("nodeKey")), node);
        }
        List<String> pathParts = new ArrayList<>();
        for (Map<String, Object> edge : edges == null ? List.<Map<String, Object>>of() : edges) {
            Map<String, Object> from = nodeMap.get(Objects.toString(edge.get("fromKey")));
            Map<String, Object> to = nodeMap.get(Objects.toString(edge.get("toKey")));
            if (from != null && to != null) {
                pathParts.add("%s(%s) -[%s]-> %s(%s)".formatted(
                        Objects.toString(from.get("label"), ""),
                        Objects.toString(from.get("nodeType"), ""),
                        Objects.toString(edge.get("relationType"), "RELATED"),
                        Objects.toString(to.get("label"), ""),
                        Objects.toString(to.get("nodeType"), "")
                ));
            }
            if (pathParts.size() >= 8) {
                break;
            }
        }
        if (!pathParts.isEmpty()) {
            return String.join("；", pathParts);
        }
        return nodes.stream()
                .limit(8)
                .map(node -> Objects.toString(node.get("label"), "") + "(" + Objects.toString(node.get("nodeType"), "") + ")")
                .reduce((a, b) -> a + " -> " + b)
                .orElse("暂无图谱路径，请先同步知识图谱。");
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
            // Neo4j 鏄寮洪摼璺紱鍚屾澶辫触涓嶅奖鍝嶆湰鍦?MySQL 鍥捐氨涓庝富涓氬姟婕旂ず銆?
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

