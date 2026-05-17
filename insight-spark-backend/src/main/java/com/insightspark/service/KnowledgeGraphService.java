package com.insightspark.service;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

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

    private final AtomicLong neo4jWriteSuccessCount = new AtomicLong();
    private final AtomicLong neo4jWriteFailureCount = new AtomicLong();
    private final AtomicLong neo4jQuerySuccessCount = new AtomicLong();
    private final AtomicLong neo4jQueryFailureCount = new AtomicLong();
    private volatile String lastNeo4jError = "";
    private volatile Instant lastNeo4jSuccessAt;
    private volatile Instant lastNeo4jFailureAt;

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

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `is_neo4j_write_audit` (
                  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `action` VARCHAR(64) NOT NULL,
                  `entity_key` VARCHAR(255) NOT NULL,
                  `relation_type` VARCHAR(64) NULL,
                  `cypher` TEXT NULL,
                  `params_json` TEXT NULL,
                  `status` VARCHAR(32) NOT NULL,
                  `error_message` TEXT NULL,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX `idx_neo4j_write_audit_status` (`status`),
                  INDEX `idx_neo4j_write_audit_created_at` (`created_at`),
                  INDEX `idx_neo4j_write_audit_entity` (`entity_key`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Neo4j write audit';
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
                    "官方表：" + Objects.toString(table.get("tableComment"), "") + "；估算行数：" + table.get("tableRows"), 2.0);
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

        Map<String, Integer> metricSync = syncBusinessMetricNodes();
        nodeCount += metricSync.getOrDefault("node", 0);
        edgeCount += metricSync.getOrDefault("edge", 0);

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
        Neo4jRuntimeConfig config = loadNeo4jRuntimeConfig();
        if (!config.enabled()) {
            return fallbackMultiHopContext(keyword, tableName, "Neo4j disabled, fallback to local MySQL knowledge graph");
        }
        try {
            return neo4jMultiHopSearch(keyword, tableName, depth, limit);
        } catch (Exception e) {
            String error = safeErrorMessage(e);
            log.warn("Neo4j multi-hop query failed, fallback to local graph: {}", error);
            return fallbackMultiHopContext(keyword, tableName, error);
        }
    }

    public List<Map<String, Object>> retrieveContext(String question, String tableName) {
        return castMapList(multiHopSearch(question, tableName, 2, 12).getOrDefault("ragContext", List.of()));
        /*
        if (question != null && !question.isBlank()) {
            for (String token : question.split("[\\s,，。；;？?]+")) {
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

    public Map<String, Object> buildSqlMappingHints(String question, String tableName, List<Map<String, Object>> graphContext) {
        List<Map<String, Object>> context = graphContext == null ? List.of() : graphContext;
        String q = Objects.toString(question, "").trim();
        List<String> tokens = splitQuestionTokens(q);
        List<Map<String, Object>> dictionaryEntries = loadDictionaryEntries(tableName);

        List<Map<String, Object>> fieldCandidates = new ArrayList<>();
        List<Map<String, Object>> formulaCandidates = new ArrayList<>();

        for (Map<String, Object> node : context) {
            String nodeType = Objects.toString(node.get("nodeType"), "").toUpperCase();
            if ("FIELD".equals(nodeType) || "OFFICIAL_FIELD".equals(nodeType)) {
                fieldCandidates.add(buildFieldCandidate(node, q, tokens));
            }
            if ("BUSINESS_METRIC".equals(nodeType)) {
                formulaCandidates.add(buildFormulaCandidate(node, q, tokens));
            }
        }

        fieldCandidates.sort((a, b) -> Double.compare(readDouble(b.get("score")), readDouble(a.get("score"))));
        formulaCandidates.sort((a, b) -> Double.compare(readDouble(b.get("score")), readDouble(a.get("score"))));

        applyDictionaryBoost(q, tokens, fieldCandidates, dictionaryEntries);
        fieldCandidates.sort((a, b) -> Double.compare(readDouble(b.get("score")), readDouble(a.get("score"))));

        List<Map<String, Object>> topFields = fieldCandidates.stream().limit(8).toList();
        List<Map<String, Object>> topFormulas = formulaCandidates.stream().limit(6).toList();

        List<Map<String, Object>> ambiguities = detectAmbiguities(topFields);
        Map<String, Object> mapping = recommendMapping(topFields, topFormulas, q);
        applyDictionaryMappingOverride(mapping, topFields, dictionaryEntries);

        return Map.of(
                "fieldCandidates", topFields,
                "formulaCandidates", topFormulas,
                "dictionaryMatches", collectDictionaryMatches(topFields),
                "ambiguities", ambiguities,
                "recommendedMapping", mapping,
                "graphReasoning", summarizeHints(topFields, topFormulas, ambiguities, mapping, dictionaryEntries)
        );
    }

    public Map<String, Object> retrieveMultiHopContext(String question, String tableName) {
        Map<String, Object> bundle = multiHopSearch(question, tableName, 3, 16);
        Map<String, Object> context = new LinkedHashMap<>(bundle);
        context.put("nodes", bundle.getOrDefault("nodes", List.of()));
        context.put("edges", bundle.getOrDefault("edges", List.of()));
        context.put("pathText", bundle.getOrDefault("pathText", ""));
        context.put("ragContext", bundle.getOrDefault("ragContext", List.of()));
        context.put("depth", bundle.getOrDefault("depth", 3));
        context.put("neo4jEnabled", bundle.getOrDefault("neo4jEnabled", neo4jEnabled));
        return context;
    }

    public Map<String, Object> retrieveMultiHopContextSafely(String question, String tableName) {
        try {
            Map<String, Object> bundle = retrieveMultiHopContext(question, tableName);
            return new LinkedHashMap<>(bundle);
        } catch (Exception e) {
            markNeo4jQueryFailure(e);
            return fallbackMultiHopContext(question, tableName, safeErrorMessage(e));
        }
    }

    public Map<String, Object> healthStatus() {
        int mysqlNodeCount = 0;
        int mysqlEdgeCount = 0;
        try {
            Integer nodes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_kg_node", Integer.class);
            Integer edges = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM is_kg_edge", Integer.class);
            mysqlNodeCount = nodes == null ? 0 : nodes;
            mysqlEdgeCount = edges == null ? 0 : edges;
        } catch (Exception ignore) {
            // keep default zero values
        }

        boolean neo4jConnected = false;
        String probeError = "";
        Neo4jRuntimeConfig cfg = loadNeo4jRuntimeConfig();
        if (cfg.enabled()) {
            try {
                neo4jQueryRows("RETURN 1 AS ping", Map.of());
                neo4jConnected = true;
            } catch (Exception e) {
                markNeo4jQueryFailure(e);
                probeError = safeErrorMessage(e);
            }
        }

        String status = (!cfg.enabled() || !neo4jConnected) ? "DEGRADED" : "UP";
        String neo4jStatus = !cfg.enabled() ? "DISABLED" : (neo4jConnected ? "UP" : "DOWN");
        String error = chooseFirstNonBlank(probeError, lastNeo4jError);

        Map<String, Object> neo4j = new LinkedHashMap<>();
        neo4j.put("enabled", cfg.enabled());
        neo4j.put("status", neo4jStatus);
        neo4j.put("httpUrl", cfg.httpUrl());
        neo4j.put("writeSuccessCount", neo4jWriteSuccessCount.get());
        neo4j.put("writeFailureCount", neo4jWriteFailureCount.get());
        neo4j.put("querySuccessCount", neo4jQuerySuccessCount.get());
        neo4j.put("queryFailureCount", neo4jQueryFailureCount.get());
        neo4j.put("lastSuccessAt", lastNeo4jSuccessAt == null ? null : lastNeo4jSuccessAt.toString());
        neo4j.put("lastFailureAt", lastNeo4jFailureAt == null ? null : lastNeo4jFailureAt.toString());
        neo4j.put("lastError", error);

        return Map.of(
                "status", status,
                "timestamp", Instant.now().toString(),
                "mysql", Map.of(
                        "nodeCount", mysqlNodeCount,
                        "edgeCount", mysqlEdgeCount,
                        "available", true
                ),
                "neo4j", neo4j,
                "degraded", !"UP".equals(status)
        );
    }

    private Map<String, Object> fallbackMultiHopContext(String question, String tableName, String error) {
        List<Map<String, Object>> nodes = List.of();
        List<Map<String, Object>> edges = List.of();
        String fallbackError = Objects.toString(error, "");
        try {
            nodes = search(question, 20);
        } catch (Exception e) {
            fallbackError = chooseFirstNonBlank(fallbackError, safeErrorMessage(e));
            log.warn("Local fallback node query failed: {}", safeErrorMessage(e));
        }
        try {
            List<String> nodeKeys = nodes.stream().map(item -> Objects.toString(item.get("nodeKey"), "")).toList();
            edges = nodeKeys.isEmpty()
                    ? List.of()
                    : jdbcTemplate.queryForList("""
                            SELECT from_key AS fromKey, to_key AS toKey, relation_type AS relationType, weight
                            FROM is_kg_edge
                            WHERE from_key IN (%s) OR to_key IN (%s)
                            ORDER BY weight DESC
                            LIMIT 80
                            """.formatted(placeholders(nodeKeys.size()), placeholders(nodeKeys.size())),
                    doubledArgs(nodeKeys));
        } catch (Exception e) {
            fallbackError = chooseFirstNonBlank(fallbackError, safeErrorMessage(e));
            log.warn("Local fallback edge query failed: {}", safeErrorMessage(e));
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("nodes", nodes);
        fallback.put("edges", edges);
        fallback.put("pathText", buildPathText(nodes, edges));
        fallback.put("ragContext", nodes);
        fallback.put("depth", 1);
        fallback.put("neo4jEnabled", loadNeo4jRuntimeConfig().enabled());
        fallback.put("neo4jFallback", true);
        fallback.put("neo4jError", fallbackError.isBlank() ? "neo4j unavailable" : fallbackError);
        fallback.put("fallbackSource", "MYSQL_KG");
        fallback.put("tableName", Objects.toString(tableName, ""));
        fallback.put("question", Objects.toString(question, ""));
        fallback.put("fallbackError", fallbackError);
        return fallback;
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
        try {
            Neo4jRuntimeConfig config = loadNeo4jRuntimeConfig();
            String payload = objectMapper.writeValueAsString(Map.of(
                    "statements", List.of(Map.of("statement", cypher, "parameters", params))
            ));
            String token = Base64.getEncoder().encodeToString((config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.httpUrl()))
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
                markNeo4jQuerySuccess();
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
            markNeo4jQuerySuccess();
            return rows;
        } catch (Exception e) {
            markNeo4jQueryFailure(e);
            throw e;
        }
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

    private Map<String, Integer> syncBusinessMetricNodes() {
        int nodeCount = 0;
        int edgeCount = 0;
        List<Map<String, Object>> models = jdbcTemplate.queryForList("""
                SELECT table_name AS tableName, model_json AS modelJson
                FROM is_business_model
                WHERE status = 'ACTIVE'
                """);
        for (Map<String, Object> model : models) {
            String tableName = Objects.toString(model.get("tableName"), "").trim();
            if (tableName.isBlank()) continue;
            Map<String, Object> parsed = parseJsonMap(model.get("modelJson"));
            List<Map<String, Object>> metrics = castMapList(parsed.get("metricDefinitions"));
            for (Map<String, Object> metric : metrics) {
                String name = Objects.toString(metric.get("name"), "").trim();
                String field = Objects.toString(metric.get("field"), "").trim();
                String formula = Objects.toString(metric.get("formula"), "").trim();
                if (name.isBlank()) continue;
                String metricKey = "metric:" + tableName + ":" + slug(name);
                String content = "formula=" + formula + "; field=" + field;
                nodeCount += upsertNode(metricKey, "BUSINESS_METRIC", name, "BUSINESS_MODEL", tableName, content, 2.5);
                String tableKey = "upload_table:" + tableName;
                edgeCount += upsertEdge(tableKey, metricKey, "HAS_METRIC", 1.5);
                if (!field.isBlank()) {
                    String fieldKey = tableKey + ":field:" + field;
                    edgeCount += upsertEdge(metricKey, fieldKey, "USES_FIELD", 1.5);
                }
            }
        }
        return Map.of("node", nodeCount, "edge", edgeCount);
    }

    private Map<String, Object> buildFieldCandidate(Map<String, Object> node, String question, List<String> tokens) {
        String label = Objects.toString(node.get("label"), "");
        String sourceId = Objects.toString(node.get("sourceId"), "");
        String content = Objects.toString(node.get("content"), "");
        double score = readDouble(node.get("weight"));
        String bag = normalizeText(label + " " + sourceId + " " + content);
        String q = normalizeText(question);
        int matched = 0;
        for (String token : tokens) {
            String normalizedToken = normalizeText(token);
            if (!normalizedToken.isBlank() && bag.contains(normalizedToken)) {
                score += 0.8;
                matched++;
            }
        }
        score += matched * 0.2;
        double dimensionScore = score + scoreDimensionBonus(q, bag, label, sourceId, content);
        double metricScore = score + scoreMetricBonus(q, bag, label, sourceId, content);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("label", label);
        candidate.put("sourceId", sourceId);
        candidate.put("nodeKey", Objects.toString(node.get("nodeKey"), ""));
        candidate.put("score", score);
        candidate.put("dimensionScore", dimensionScore);
        candidate.put("metricScore", metricScore);
        candidate.put("dictionaryMatched", false);
        candidate.put("dictionaryHitTerm", "");
        candidate.put("dictionaryHitSynonym", "");
        candidate.put("dictionaryBoost", 0D);
        return candidate;
    }

    private Map<String, Object> buildFormulaCandidate(Map<String, Object> node, String question, List<String> tokens) {
        String name = Objects.toString(node.get("label"), "");
        String sourceId = Objects.toString(node.get("sourceId"), "");
        String formula = Objects.toString(node.get("content"), "");
        String bag = normalizeText(name + " " + sourceId + " " + formula);
        String q = normalizeText(question);
        double weight = readDouble(node.get("weight"));
        double score = weight;
        for (String token : tokens) {
            String normalizedToken = normalizeText(token);
            if (!normalizedToken.isBlank() && bag.contains(normalizedToken)) {
                score += 0.6;
            }
        }
        if (containsAny(q,
                "\u589e\u957f", "\u540c\u6bd4", "\u73af\u6bd4", "\u8d8b\u52bf", "\u53d8\u5316", "\u589e\u957f\u7387", "\u5360\u6bd4", "\u5747\u503c", "\u5e73\u5747",
                "growth", "yoy", "mom", "trend", "change", "rate", "ratio", "share", "avg")) {
            if (containsAny(bag,
                    "rate", "ratio", "avg", "sum", "count", "profit", "sales", "amount", "qty", "revenue", "margin", "growth", "share",
                    "\u6bd4\u7387", "\u5360\u6bd4", "\u540c\u6bd4", "\u73af\u6bd4", "\u5e73\u5747", "\u5747\u503c")) {
                score += 1.2;
            } else {
                score += 0.2;
            }
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("name", name);
        candidate.put("sourceId", sourceId);
        candidate.put("formula", formula);
        candidate.put("weight", weight);
        candidate.put("score", score);
        return candidate;
    }

    private List<Map<String, Object>> detectAmbiguities(List<Map<String, Object>> fields) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : fields) {
            String normalized = normalizeLabel(Objects.toString(item.get("label"), ""));
            if (normalized.isBlank()) continue;
            grouped.computeIfAbsent(normalized, k -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            List<String> candidates = entry.getValue().stream()
                    .map(item -> Objects.toString(item.get("sourceId"), ""))
                    .filter(v -> !v.isBlank())
                    .toList();
            out.add(new LinkedHashMap<>(Map.of(
                    "term", entry.getKey(),
                    "candidates", candidates,
                    "resolution", candidates.isEmpty() ? "manual" : candidates.get(0)
            )));
        }
        return out;
    }

    private Map<String, Object> recommendMapping(List<Map<String, Object>> fields, List<Map<String, Object>> formulas, String question) {
        String dimensionKey = "";
        String metricKey = "";
        String metricFormula = "";
        double bestDimensionScore = Double.NEGATIVE_INFINITY;
        double bestMetricScore = Double.NEGATIVE_INFINITY;
        String q = normalizeText(question);
        for (Map<String, Object> item : fields) {
            String sourceId = Objects.toString(item.get("sourceId"), "");
            if (sourceId.isBlank()) {
                continue;
            }
            String bag = normalizeText(Objects.toString(item.get("label"), "") + " " + sourceId + " " + Objects.toString(item.get("nodeKey"), ""));
            double dimensionScore = readDouble(item.get("dimensionScore"));
            double metricScore = readDouble(item.get("metricScore"));
            dimensionScore += scoreDimensionBonus(q, bag, Objects.toString(item.get("label"), ""), sourceId, Objects.toString(item.get("nodeKey"), ""));
            metricScore += scoreMetricBonus(q, bag, Objects.toString(item.get("label"), ""), sourceId, Objects.toString(item.get("nodeKey"), ""));
            if (dimensionScore > bestDimensionScore) {
                bestDimensionScore = dimensionScore;
                dimensionKey = sourceId;
            }
            if (metricScore > bestMetricScore) {
                bestMetricScore = metricScore;
                metricKey = sourceId;
            }
        }
        if (!formulas.isEmpty()) {
            metricFormula = Objects.toString(formulas.get(0).get("formula"), "");
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("dimensionKey", dimensionKey);
        mapping.put("metricKey", metricKey);
        mapping.put("metricFormula", metricFormula);
        return mapping;
    }

    private List<String> splitQuestionTokens(String question) {
        String q = normalizeText(question);
        if (q.isBlank()) return List.of();
        String[] parts = q.split("[^a-z0-9\u4e00-\u9fa5]+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.length() >= 2) out.add(token);
        }
        return out;
    }

    private String normalizeLabel(String label) {
        return Objects.toString(label, "").toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "").trim();
    }

    private String summarizeHints(List<Map<String, Object>> fields, List<Map<String, Object>> formulas,
                                  List<Map<String, Object>> ambiguities, Map<String, Object> mapping,
                                  List<Map<String, Object>> dictionaryEntries) {
        long dictionaryHitCount = fields.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("dictionaryMatched")))
                .count();
        return "fields=" + fields.size() + ", formulas=" + formulas.size()
                + ", ambiguities=" + ambiguities.size()
                + ", dictionaryEntries=" + dictionaryEntries.size()
                + ", dictionaryHits=" + dictionaryHitCount
                + ", mapping=" + mapping;
    }

    private List<Map<String, Object>> loadDictionaryEntries(String tableName) {
        String table = Objects.toString(tableName, "").trim();
        if (table.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT model_json AS modelJson
                FROM is_business_model
                WHERE status = 'ACTIVE' AND table_name = ?
                ORDER BY updated_at DESC
                LIMIT 10
                """, table);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> parsed = parseJsonMap(row.get("modelJson"));
            List<Map<String, Object>> entries = castMapList(parsed.get("dictionaryEntries"));
            for (Map<String, Object> entry : entries) {
                String term = Objects.toString(entry.get("term"), "").trim();
                String field = Objects.toString(entry.get("field"), "").trim();
                String synonyms = Objects.toString(entry.get("synonyms"), "").trim();
                if (term.isBlank() && field.isBlank() && synonyms.isBlank()) {
                    continue;
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("term", term);
                normalized.put("field", field);
                normalized.put("synonyms", synonyms);
                result.add(normalized);
            }
        }
        return result;
    }

    private void applyDictionaryBoost(String question, List<String> tokens,
                                      List<Map<String, Object>> fieldCandidates,
                                      List<Map<String, Object>> dictionaryEntries) {
        if (fieldCandidates.isEmpty() || dictionaryEntries.isEmpty()) {
            return;
        }
        String q = normalizeText(question);
        for (Map<String, Object> candidate : fieldCandidates) {
            String sourceId = normalizeText(Objects.toString(candidate.get("sourceId"), ""));
            if (sourceId.isBlank()) {
                continue;
            }
            double boost = 0D;
            String hitTerm = "";
            String hitSynonym = "";
            for (Map<String, Object> entry : dictionaryEntries) {
                String field = normalizeText(Objects.toString(entry.get("field"), ""));
                if (field.isBlank()) {
                    continue;
                }
                if (!sourceId.equals(field) && !sourceId.endsWith("." + field)) {
                    continue;
                }
                String term = Objects.toString(entry.get("term"), "").trim();
                List<String> synonyms = splitSynonyms(Objects.toString(entry.get("synonyms"), ""));
                boolean matched = false;
                if (!term.isBlank()) {
                    String normalizedTerm = normalizeText(term);
                    if (!normalizedTerm.isBlank() && (q.contains(normalizedTerm) || tokens.contains(normalizedTerm))) {
                        boost += 2.2;
                        hitTerm = term;
                        matched = true;
                    }
                }
                if (!matched) {
                    for (String synonym : synonyms) {
                        String normalized = normalizeText(synonym);
                        if (!normalized.isBlank() && (q.contains(normalized) || tokens.contains(normalized))) {
                            boost += 1.6;
                            hitSynonym = synonym;
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) {
                    break;
                }
            }
            if (boost > 0D) {
                candidate.put("score", readDouble(candidate.get("score")) + boost);
                candidate.put("dimensionScore", readDouble(candidate.get("dimensionScore")) + boost);
                candidate.put("metricScore", readDouble(candidate.get("metricScore")) + boost);
                candidate.put("dictionaryMatched", true);
                candidate.put("dictionaryHitTerm", hitTerm);
                candidate.put("dictionaryHitSynonym", hitSynonym);
                candidate.put("dictionaryBoost", boost);
            }
        }
    }

    private void applyDictionaryMappingOverride(Map<String, Object> mapping, List<Map<String, Object>> fields,
                                                List<Map<String, Object>> dictionaryEntries) {
        if (mapping == null || dictionaryEntries.isEmpty() || fields.isEmpty()) {
            return;
        }
        Map<String, Object> preferred = fields.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("dictionaryMatched")))
                .max((a, b) -> Double.compare(readDouble(a.get("score")), readDouble(b.get("score"))))
                .orElse(null);
        if (preferred == null) {
            return;
        }
        String preferredField = Objects.toString(preferred.get("sourceId"), "");
        if (preferredField.isBlank()) {
            return;
        }
        String currentDimension = Objects.toString(mapping.getOrDefault("dimensionKey", ""), "");
        String currentMetric = Objects.toString(mapping.getOrDefault("metricKey", ""), "");
        if (currentDimension.isBlank()) {
            mapping.put("dimensionKey", preferredField);
        }
        if (currentMetric.isBlank()) {
            mapping.put("metricKey", preferredField);
        }
        if (!Objects.equals(currentDimension, preferredField)) {
            mapping.put("dictionaryDimensionKey", preferredField);
        }
    }

    private List<Map<String, Object>> collectDictionaryMatches(List<Map<String, Object>> fields) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> item : fields) {
            if (!Boolean.TRUE.equals(item.get("dictionaryMatched"))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", Objects.toString(item.get("sourceId"), ""));
            row.put("label", Objects.toString(item.get("label"), ""));
            row.put("hitTerm", Objects.toString(item.get("dictionaryHitTerm"), ""));
            row.put("hitSynonym", Objects.toString(item.get("dictionaryHitSynonym"), ""));
            row.put("boost", readDouble(item.get("dictionaryBoost")));
            matches.add(row);
        }
        return matches;
    }

    private List<String> splitSynonyms(String text) {
        String value = Objects.toString(text, "").trim();
        if (value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : value.split("[,，;；\\s]+")) {
            String item = token.trim();
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private double readDouble(Object value) {
        try {
            return Double.parseDouble(Objects.toString(value, "0"));
        } catch (Exception e) {
            return 0D;
        }
    }

    private Map<String, Object> parseJsonMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(Objects.toString(e.getKey(), ""), e.getValue());
            }
            return out;
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String slug(String text) {
        String normalized = Objects.toString(text, "").toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "metric" : normalized;
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
        Neo4jRuntimeConfig config = loadNeo4jRuntimeConfig();
        if (!config.enabled()) {
            return;
        }
        String action = inferNeo4jWriteAction(cypher, params);
        String entityKey = inferNeo4jEntityKey(params);
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "statements", List.of(Map.of("statement", cypher, "parameters", params))
            ));
            String token = Base64.getEncoder().encodeToString((config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.httpUrl()))
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
            markNeo4jWriteSuccess();
        } catch (Exception e) {
            markNeo4jWriteFailure(e);
            String error = safeErrorMessage(e);
            log.warn("Neo4j write failed, MySQL graph remains available; action={}, entityKey={}, error={}", action, entityKey, error);
            recordNeo4jWriteAudit(action, entityKey, params, cypher, error);
        }
    }

    private Neo4jRuntimeConfig loadNeo4jRuntimeConfig() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT uri, username, password, database_name AS databaseName, enabled
                    FROM is_neo4j_runtime_config
                    WHERE id = 1
                    """);
            if (rows.isEmpty()) {
                return defaultNeo4jRuntimeConfig();
            }
            Map<String, Object> row = rows.get(0);
            String uri = Objects.toString(row.getOrDefault("uri", neo4jHttpUrl), neo4jHttpUrl);
            String databaseName = Objects.toString(row.getOrDefault("databaseName", "neo4j"), "neo4j");
            String username = Objects.toString(row.getOrDefault("username", neo4jUsername), neo4jUsername);
            String password = Objects.toString(row.getOrDefault("password", ""), "");
            if (password.isBlank()) {
                password = neo4jPassword;
            }
            boolean enabled = parseBoolean(row.get("enabled"), neo4jEnabled);
            return new Neo4jRuntimeConfig(toNeo4jHttpUrl(uri, databaseName), username, password, enabled);
        } catch (Exception ignored) {
            return defaultNeo4jRuntimeConfig();
        }
    }

    private Neo4jRuntimeConfig defaultNeo4jRuntimeConfig() {
        return new Neo4jRuntimeConfig(neo4jHttpUrl, neo4jUsername, neo4jPassword, neo4jEnabled);
    }

    private String toNeo4jHttpUrl(String uri, String databaseName) {
        String text = Objects.toString(uri, "").trim();
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return text.contains("/tx/commit") ? text : text.replaceAll("/+$", "") + "/db/" + databaseName + "/tx/commit";
        }
        if (text.startsWith("bolt://")) {
            String host = text.substring("bolt://".length()).replaceAll("/+$", "").replace(":7687", ":7474");
            return "http://" + host + "/db/" + databaseName + "/tx/commit";
        }
        return neo4jHttpUrl;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? defaultValue : Boolean.parseBoolean(text) || "1".equals(text);
    }

    private record Neo4jRuntimeConfig(String httpUrl, String username, String password, boolean enabled) {
    }

    private void markNeo4jWriteSuccess() {
        neo4jWriteSuccessCount.incrementAndGet();
        lastNeo4jSuccessAt = Instant.now();
        lastNeo4jError = "";
    }

    private void markNeo4jWriteFailure(Exception e) {
        neo4jWriteFailureCount.incrementAndGet();
        lastNeo4jFailureAt = Instant.now();
        lastNeo4jError = safeErrorMessage(e);
    }

    private void markNeo4jQuerySuccess() {
        neo4jQuerySuccessCount.incrementAndGet();
        lastNeo4jSuccessAt = Instant.now();
        lastNeo4jError = "";
    }

    private void markNeo4jQueryFailure(Exception e) {
        neo4jQueryFailureCount.incrementAndGet();
        lastNeo4jFailureAt = Instant.now();
        lastNeo4jError = safeErrorMessage(e);
    }

    private String chooseFirstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private void recordNeo4jWriteAudit(String action, String entityKey, Map<String, Object> params,
                                       String cypher, String errorMessage) {
        try {
            String relationType = Objects.toString(params.getOrDefault("relationType", ""), "");
            String paramsJson = objectMapper.writeValueAsString(params);
            jdbcTemplate.update("""
                    INSERT INTO is_neo4j_write_audit(action, entity_key, relation_type, cypher, params_json, status, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Objects.toString(action, "WRITE"),
                    Objects.toString(entityKey, ""),
                    relationType,
                    cypher,
                    paramsJson,
                    "FAILED",
                    errorMessage);
        } catch (Exception auditError) {
            log.warn("Neo4j write audit persist failed: {}", safeErrorMessage(auditError));
        }
    }

    private String inferNeo4jWriteAction(String cypher, Map<String, Object> params) {
        if (params.containsKey("nodeKey")) {
            return "NODE_UPSERT";
        }
        if (params.containsKey("fromKey") && params.containsKey("toKey")) {
            return "EDGE_UPSERT";
        }
        String text = Objects.toString(cypher, "").toUpperCase();
        if (text.contains("MERGE (N:INSIGHTNODE")) {
            return "NODE_UPSERT";
        }
        if (text.contains("MERGE (A)-[R:RELATED")) {
            return "EDGE_UPSERT";
        }
        return "WRITE";
    }

    private String inferNeo4jEntityKey(Map<String, Object> params) {
        if (params.containsKey("nodeKey")) {
            return Objects.toString(params.get("nodeKey"), "");
        }
        if (params.containsKey("fromKey") || params.containsKey("toKey")) {
            return Objects.toString(params.getOrDefault("fromKey", ""), "")
                    + "->" + Objects.toString(params.getOrDefault("toKey", ""), "")
                    + ":" + Objects.toString(params.getOrDefault("relationType", ""), "");
        }
        return "";
    }

    private String normalizeText(String text) {
        return Objects.toString(text, "").toLowerCase().replaceAll("[^a-z0-9\u4e00-\u9fa5]+", " ").trim();
    }

    private boolean containsAny(String text, String... keywords) {
        String normalized = normalizeText(text);
        for (String keyword : keywords) {
            String k = normalizeText(keyword);
            if (!k.isBlank() && normalized.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private double scoreDimensionBonus(String question, String bag, String label, String sourceId, String content) {
        double score = 0D;
        String q = normalizeText(question);
        boolean timeIntent = containsAny(q,
                "\u65f6\u95f4", "\u65e5\u671f", "\u5929", "\u5468", "\u6708", "\u5b63\u5ea6", "\u5e74",
                "time", "date", "day", "week", "month", "quarter", "year", "trend");
        boolean groupIntent = containsAny(q,
                "\u5730\u533a", "\u7701", "\u5e02", "\u5206\u7c7b", "\u7c7b\u522b", "\u7c7b\u578b", "\u540d\u79f0",
                "\u5ba2\u6237", "\u4ea7\u54c1", "\u54c1\u724c", "\u7ec4",
                "region", "province", "city", "category", "type", "class", "name", "customer", "product", "brand", "segment", "group");
        if (timeIntent && containsAny(bag,
                "\u65e5\u671f", "\u65f6\u95f4", "\u5e74", "\u6708", "\u5929", "\u5b63\u5ea6", "\u5468",
                "date", "time", "year", "month", "day", "quarter", "week")) {
            score += 2.0;
        }
        if (groupIntent && containsAny(bag,
                "\u540d\u79f0", "\u7c7b\u578b", "\u5730\u533a", "\u7701", "\u5e02", "\u5206\u7c7b", "\u7c7b\u522b",
                "\u54c1\u724c", "\u5ba2\u6237", "\u4ea7\u54c1", "\u7ec4",
                "name", "type", "region", "province", "city", "category", "kind", "class", "brand", "customer", "product", "segment")) {
            score += 1.5;
        }
        if (containsAny(bag, "id", "code", "key", "\u7f16\u53f7", "\u7f16\u7801")
                && !containsAny(q, "id", "code", "key", "\u7f16\u53f7", "\u7f16\u7801")) {
            score -= 1.0;
        }
        if (containsAny(content, "TEXT", "VARCHAR", "CHAR")
                && !containsAny(q, "\u8ba1\u6570", "\u6c42\u548c", "\u5e73\u5747", "\u603b\u8ba1", "\u91d1\u989d", "\u9500\u552e", "\u6536\u5165", "\u5229\u6da6", "\u6570\u91cf",
                "count", "sum", "avg", "total", "amount", "sales", "revenue", "profit", "qty")) {
            score += 0.3;
        }
        if (containsAny(label + " " + sourceId,
                "\u65e5\u671f", "\u65f6\u95f4", "\u5e74", "\u6708",
                "date", "time", "year", "month")) {
            score += timeIntent ? 0.8 : 0.2;
        }
        return score;
    }

    private double scoreMetricBonus(String question, String bag, String label, String sourceId, String content) {
        double score = 0D;
        String q = normalizeText(question);
        boolean metricIntent = containsAny(q,
                "\u6307\u6807", "\u91d1\u989d", "\u9500\u552e", "\u6536\u5165", "\u5229\u6da6", "\u6570\u91cf", "\u8ba1\u6570", "\u6c42\u548c", "\u5e73\u5747", "\u603b\u8ba1", "\u6bd4\u7387", "\u5355\u4ef7", "\u503c", "\u589e\u957f", "\u8d8b\u52bf", "\u5360\u6bd4",
                "metric", "amount", "sales", "revenue", "profit", "qty", "count", "sum", "avg", "total", "ratio", "rate", "price", "value", "growth", "trend", "share");
        if (metricIntent && containsAny(bag,
                "\u91d1\u989d", "\u9500\u552e", "\u6536\u5165", "\u5229\u6da6", "\u6570\u91cf", "\u8ba1\u6570", "\u603b\u8ba1", "\u5e73\u5747", "\u6bd4\u7387", "\u5355\u4ef7", "\u503c", "\u5f97\u5206", "\u589e\u957f", "\u5360\u6bd4",
                "amount", "sales", "sale", "revenue", "profit", "qty", "count", "number", "total", "avg", "sum", "price", "rate", "ratio", "value", "score", "growth", "share")) {
            score += 2.0;
        }
        if (containsAny(content, "NUMBER", "DECIMAL", "INT", "BIGINT", "FLOAT", "DOUBLE")) {
            score += 1.2;
        }
        if (containsAny(bag, "id", "code", "key", "\u7f16\u53f7", "\u7f16\u7801")
                && !containsAny(q, "id", "code", "key", "\u7f16\u53f7", "\u7f16\u7801")) {
            score -= 1.5;
        }
        if (containsAny(label + " " + sourceId,
                "\u91d1\u989d", "\u9500\u552e", "\u5229\u6da6", "\u6570\u91cf", "\u8ba1\u6570", "\u603b\u8ba1", "\u6536\u5165", "\u5355\u4ef7",
                "amount", "sales", "profit", "qty", "count", "total", "revenue", "price")) {
            score += 0.8;
        }
        return score;
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

