package com.insightspark.c.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@DependsOn("authService")
public class StackCSchemaInitializer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initStackCTables() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_system_announcement` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `title` VARCHAR(255) NOT NULL COMMENT '公告标题',
                      `content` LONGTEXT NOT NULL COMMENT '公告正文',
                      `audience` VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '受众：ALL / USER / ADMIN',
                      `pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶',
                      `priority` INT NOT NULL DEFAULT 0 COMMENT '排序优先级',
                      `publish_status` VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态',
                      `published_at` DATETIME NULL COMMENT '发布时间',
                      `expire_at` DATETIME NULL COMMENT '过期时间',
                      `created_by` VARCHAR(64) NULL COMMENT '发布人 user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_announcement_audience_published` (`audience`, `publish_status`, `published_at`),
                      INDEX `idx_announcement_expire` (`expire_at`),
                      CONSTRAINT `fk_announcement_creator` FOREIGN KEY (`created_by`) REFERENCES `is_user` (`user_id`)
                        ON DELETE SET NULL ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统公告';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `owner_user_id` VARCHAR(64) NOT NULL COMMENT '所有者 user_id',
                      `name` VARCHAR(255) NOT NULL COMMENT '看板名称',
                      `description` VARCHAR(1000) NULL COMMENT '看板描述',
                      `layout_json` LONGTEXT NOT NULL COMMENT '画布与组件布局 JSON',
                      `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否公共看板',
                      `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
                      `share_token` VARCHAR(64) NULL COMMENT '分享 token',
                      `share_expire_at` DATETIME NULL COMMENT '分享过期时间',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_dashboard_share_token` (`share_token`),
                      INDEX `idx_dashboard_owner` (`owner_user_id`),
                      INDEX `idx_dashboard_public_status` (`is_public`, `status`),
                      CONSTRAINT `fk_dashboard_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据看板';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard_component` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '看板内组件主键',
                      `dashboard_id` BIGINT NOT NULL COMMENT '看板 id',
                      `chart_id` BIGINT NOT NULL COMMENT '兼容历史图表 is_chat_query_history.id',
                      `artifact_id` BIGINT NULL COMMENT '对话产物 is_chat_conversation_artifact.id',
                      `turn_id` BIGINT NULL COMMENT '对话轮次 is_chat_conversation_turn.id',
                      `position_config` VARCHAR(512) NOT NULL DEFAULT '{"x":0,"y":0,"w":6,"h":4}' COMMENT '位置 JSON 冗余',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (`id`),
                      KEY `idx_dashboard_component_board` (`dashboard_id`),
                      KEY `idx_dashboard_component_chart` (`chart_id`),
                      KEY `idx_dashboard_component_artifact` (`artifact_id`),
                      KEY `idx_dashboard_component_turn` (`turn_id`),
                      CONSTRAINT `fk_dashboard_component_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板与对话图表关联';
                    """);
            addColumnIfMissing("is_dashboard_component", "artifact_id",
                    "`artifact_id` BIGINT NULL COMMENT '对话产物 is_chat_conversation_artifact.id'");
            addColumnIfMissing("is_dashboard_component", "turn_id",
                    "`turn_id` BIGINT NULL COMMENT '对话轮次 is_chat_conversation_turn.id'");
            addIndexIfMissing("is_dashboard_component", "idx_dashboard_component_artifact",
                    "CREATE INDEX `idx_dashboard_component_artifact` ON `is_dashboard_component` (`artifact_id`)");
            addIndexIfMissing("is_dashboard_component", "idx_dashboard_component_turn",
                    "CREATE INDEX `idx_dashboard_component_turn` ON `is_dashboard_component` (`turn_id`)");

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_annotation` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '作者 user_id',
                      `target_type` VARCHAR(32) NOT NULL COMMENT '对象类型',
                      `target_id` BIGINT NOT NULL COMMENT '对象主键',
                      `dashboard_id` BIGINT NULL COMMENT '所属看板 id',
                      `bind_json` JSON NULL COMMENT '绑定上下文 JSON',
                      `content` TEXT NOT NULL COMMENT '批注正文',
                      `tag` VARCHAR(64) NULL COMMENT '批注标签',
                      `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_annotation_user` (`user_id`),
                      INDEX `idx_annotation_target` (`target_type`, `target_id`),
                      INDEX `idx_annotation_dashboard` (`dashboard_id`),
                      CONSTRAINT `fk_annotation_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE,
                      CONSTRAINT `fk_annotation_dashboard` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
                        ON DELETE SET NULL ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务批注';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_comment` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `parent_id` BIGINT NULL COMMENT '父评论 id',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '作者 user_id',
                      `target_type` VARCHAR(32) NOT NULL COMMENT '挂载对象类型',
                      `target_id` BIGINT NOT NULL COMMENT '挂载对象主键',
                      `content` TEXT NOT NULL COMMENT '评论内容',
                      `mentions_json` JSON NULL COMMENT '提及用户 JSON',
                      `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_comment_parent` (`parent_id`),
                      INDEX `idx_comment_user` (`user_id`),
                      INDEX `idx_comment_target` (`target_type`, `target_id`),
                      CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协同评论';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_system_config` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `config_key` VARCHAR(128) NOT NULL COMMENT '配置键',
                      `config_value` LONGTEXT NULL COMMENT '配置值',
                      `value_type` VARCHAR(32) NOT NULL DEFAULT 'STRING' COMMENT '值类型',
                      `category` VARCHAR(64) NULL COMMENT '配置分组',
                      `description` VARCHAR(512) NULL COMMENT '配置说明',
                      `updated_by` VARCHAR(64) NULL COMMENT '最后修改人 user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_system_config_key` (`config_key`),
                      INDEX `idx_system_config_category` (`category`),
                      CONSTRAINT `fk_system_config_updater` FOREIGN KEY (`updated_by`) REFERENCES `is_user` (`user_id`)
                        ON DELETE SET NULL ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_perf_intervention` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `audit_log_id` BIGINT NOT NULL COMMENT '关联 is_sql_audit_log.id',
                      `action` VARCHAR(32) NOT NULL COMMENT '处置动作',
                      `operator_user_id` VARCHAR(64) NOT NULL COMMENT '操作人 user_id',
                      `remark` VARCHAR(500) NULL COMMENT '备注',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_perf_intervention_audit` (`audit_log_id`),
                      INDEX `idx_perf_intervention_created` (`created_at`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='性能治理处置记录';
                    """);
        } catch (Exception e) {
            log.error("全栈 C 表初始化失败", e);
            throw e;
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (columnCount == null || columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + definition);
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String ddl) {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, tableName, indexName);
        if (indexCount == null || indexCount == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
