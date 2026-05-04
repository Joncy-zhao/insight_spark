package com.insightspark.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class KnowledgeDocumentService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermissionService permissionService;

    @PostConstruct
    public void initTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS is_knowledge_doc (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  title VARCHAR(255) NOT NULL,
                  file_name VARCHAR(255),
                  doc_type VARCHAR(50),
                  content LONGTEXT,
                  created_by VARCHAR(64),
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档';
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS is_knowledge_chunk (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  doc_id BIGINT NOT NULL,
                  chunk_index INT NOT NULL,
                  chunk_text TEXT NOT NULL,
                  keywords VARCHAR(1000),
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                  INDEX idx_knowledge_chunk_doc(doc_id),
                  INDEX idx_knowledge_chunk_index(chunk_index)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档切片';
                """);
    }

    public Map<String, Object> upload(MultipartFile file) throws IOException {
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "knowledge.txt");
        String lowerName = fileName.toLowerCase();
        if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".md")) {
            throw new IllegalArgumentException("知识文档当前仅支持 .txt / .md 文件");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String title = removeExtension(fileName);
        String docType = lowerName.endsWith(".md") ? "MARKDOWN" : "TEXT";
        jdbcTemplate.update("""
                INSERT INTO is_knowledge_doc(title, file_name, doc_type, content, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, title, fileName, docType, content, permissionService.currentUserId());
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        index(id);
        return Map.of("id", id, "title", title, "fileName", fileName, "docType", docType);
    }

    public List<Map<String, Object>> listDocs() {
        return jdbcTemplate.queryForList("""
                SELECT d.id, d.title, d.file_name AS fileName, d.doc_type AS docType,
                       d.created_by AS createdBy, d.created_at AS createdAt,
                       COUNT(c.id) AS chunkCount
                FROM is_knowledge_doc d
                LEFT JOIN is_knowledge_chunk c ON c.doc_id = d.id
                GROUP BY d.id, d.title, d.file_name, d.doc_type, d.created_by, d.created_at
                ORDER BY d.created_at DESC
                LIMIT 100
                """);
    }

    public List<Map<String, Object>> listChunks(Long docId) {
        return jdbcTemplate.queryForList("""
                SELECT c.id, c.doc_id AS docId, d.title, c.chunk_index AS chunkIndex,
                       c.chunk_text AS chunkText, c.keywords, c.created_at AS createdAt
                FROM is_knowledge_chunk c
                JOIN is_knowledge_doc d ON d.id = c.doc_id
                WHERE c.doc_id = ?
                ORDER BY c.chunk_index ASC
                LIMIT 100
                """, docId);
    }

    public Map<String, Object> index(Long docId) {
        List<Map<String, Object>> docs = jdbcTemplate.queryForList("""
                SELECT id, title, content
                FROM is_knowledge_doc
                WHERE id = ?
                """, docId);
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("知识文档不存在：" + docId);
        }
        String content = Objects.toString(docs.get(0).get("content"), "");
        jdbcTemplate.update("DELETE FROM is_knowledge_chunk WHERE doc_id = ?", docId);
        List<String> chunks = splitChunks(content, 700, 100);
        for (int i = 0; i < chunks.size(); i++) {
            jdbcTemplate.update("""
                    INSERT INTO is_knowledge_chunk(doc_id, chunk_index, chunk_text, keywords)
                    VALUES (?, ?, ?, ?)
                    """, docId, i + 1, chunks.get(i), extractKeywords(chunks.get(i)));
        }
        return Map.of("docId", docId, "chunkCount", chunks.size());
    }

    public List<Map<String, Object>> search(String question, int limit) {
        List<String> terms = extractSearchTerms(question);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        Map<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        for (String term : terms) {
            String like = "%" + term + "%";
            String sql = "SELECT c.doc_id AS docId, d.title, c.chunk_index AS chunkIndex, c.chunk_text AS chunkText,\n" +
                    "       CONCAT('《', d.title, '》 第 ', c.chunk_index, ' 段') AS source\n" +
                    "FROM is_knowledge_chunk c\n" +
                    "JOIN is_knowledge_doc d ON d.id = c.doc_id\n" +
                    "WHERE c.chunk_text LIKE ? OR c.keywords LIKE ?\n" +
                    "ORDER BY c.created_at DESC\n" +
                    "LIMIT " + safeLimit;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, like, like);
            for (Map<String, Object> row : rows) {
                dedup.putIfAbsent(row.get("docId") + ":" + row.get("chunkIndex"), row);
            }
            if (dedup.size() >= safeLimit) {
                break;
            }
        }
        return dedup.values().stream().limit(safeLimit).toList();
    }

    private List<String> splitChunks(String content, int chunkSize, int overlap) {
        String text = content == null ? "" : content.replace("\r\n", "\n").trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            chunks.add(text.substring(start, end).trim());
            if (end == text.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return chunks;
    }

    private List<String> extractSearchTerms(String question) {
        String text = Objects.toString(question, "").trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String token : text.split("[\\s,，。；;、?？!！]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        if (terms.isEmpty() && text.length() >= 2) {
            terms.add(text.substring(0, Math.min(8, text.length())));
        }
        return terms;
    }

    private String extractKeywords(String text) {
        String keywords = String.join(",", extractSearchTerms(text));
        return keywords.length() <= 1000 ? keywords : keywords.substring(0, 1000);
    }

    private String removeExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex <= 0 ? filename : filename.substring(0, dotIndex);
    }
}
