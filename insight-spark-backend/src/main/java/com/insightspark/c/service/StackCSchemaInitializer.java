package com.insightspark.c.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@DependsOn("authService")
public class StackCSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(StackCSchemaInitializer.class);

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
                      `group_name` VARCHAR(128) NULL COMMENT '分组/用途',
                      `layout_json` LONGTEXT NOT NULL COMMENT '画布与组件布局 JSON',
                      `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否公共看板',
                      `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
                      `share_token` VARCHAR(64) NULL COMMENT '分享 token',
                      `share_expire_at` DATETIME NULL COMMENT '分享过期时间',
                      `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '访问量（打开次数）',
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

            addColumnIfMissing("is_dashboard", "group_name",
                    "`group_name` VARCHAR(128) NULL COMMENT '分组/用途'");
            addIndexIfMissing("is_dashboard", "idx_dashboard_group_name",
                    "CREATE INDEX `idx_dashboard_group_name` ON `is_dashboard` (`group_name`)");

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard_group` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `parent_id` BIGINT NULL COMMENT '父分组 id',
                      `owner_user_id` VARCHAR(64) NULL COMMENT '用户个人分组所有者，NULL 表示平台分组',
                      `name` VARCHAR(128) NOT NULL COMMENT '分组名称',
                      `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (`id`),
                      KEY `idx_dashboard_group_parent` (`parent_id`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板分组';
                    """);
            addColumnIfMissing("is_dashboard_group", "owner_user_id",
                    "`owner_user_id` VARCHAR(64) NULL COMMENT '用户个人分组所有者，NULL 表示平台分组' AFTER `parent_id`");
            addIndexIfMissing("is_dashboard_group", "idx_dashboard_group_owner",
                    "CREATE INDEX `idx_dashboard_group_owner` ON `is_dashboard_group` (`owner_user_id`)");
            addColumnIfMissing("is_dashboard", "group_id",
                    "`group_id` BIGINT NULL COMMENT '所属分组 id' AFTER `group_name`");
            addIndexIfMissing("is_dashboard", "idx_dashboard_group_id",
                    "CREATE INDEX `idx_dashboard_group_id` ON `is_dashboard` (`group_id`)");
            addColumnIfMissing("is_dashboard", "view_count",
                    "`view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '访问量（打开次数）' AFTER `share_expire_at`");
            addColumnIfMissing("is_dashboard", "author_user_id",
                    "`author_user_id` VARCHAR(64) NULL COMMENT '看板原作者 user_id' AFTER `owner_user_id`");
            addColumnIfMissing("is_dashboard", "source_dashboard_id",
                    "`source_dashboard_id` BIGINT NULL COMMENT '另存来源看板 id' AFTER `author_user_id`");
            addColumnIfMissing("is_dashboard", "save_as_user_id",
                    "`save_as_user_id` VARCHAR(64) NULL COMMENT '另存/复制生成者 user_id' AFTER `source_dashboard_id`");
            addColumnIfMissing("is_dashboard", "publisher_user_id",
                    "`publisher_user_id` VARCHAR(64) NULL COMMENT '执行发布者 user_id' AFTER `save_as_user_id`");
            backfillDashboardAuthorAndPublisher();

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
            addColumnIfMissing("is_annotation", "is_hidden",
                    "`is_hidden` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否隐藏' AFTER `is_deleted`");

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
                    CREATE TABLE IF NOT EXISTS `is_dashboard_follow` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `dashboard_id` BIGINT NOT NULL COMMENT '看板 id',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '关注者 user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_dashboard_follow_user` (`dashboard_id`, `user_id`),
                      INDEX `idx_dashboard_follow_user` (`user_id`),
                      CONSTRAINT `fk_dashboard_follow_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE,
                      CONSTRAINT `fk_dashboard_follow_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板关注';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_team` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `name` VARCHAR(128) NOT NULL COMMENT '团队名称',
                      `description` VARCHAR(500) NULL COMMENT '团队说明',
                      `owner_user_id` VARCHAR(64) NOT NULL COMMENT '创建者 user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_team_owner` (`owner_user_id`),
                      CONSTRAINT `fk_team_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作团队';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_team_member` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `team_id` BIGINT NOT NULL COMMENT '团队 id',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '成员 user_id',
                      `member_role` VARCHAR(32) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER/ADMIN/MEMBER',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_team_member` (`team_id`, `user_id`),
                      INDEX `idx_team_member_user` (`user_id`),
                      CONSTRAINT `fk_team_member_team` FOREIGN KEY (`team_id`) REFERENCES `is_team` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE,
                      CONSTRAINT `fk_team_member_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队成员';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard_team_permission` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `dashboard_id` BIGINT NOT NULL COMMENT '看板 id',
                      `team_id` BIGINT NOT NULL COMMENT '团队 id',
                      `permission_type` VARCHAR(32) NOT NULL DEFAULT 'READ' COMMENT 'READ/EDIT',
                      `granted_by` VARCHAR(64) NULL COMMENT '授权人 user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_dashboard_team_perm` (`dashboard_id`, `team_id`, `permission_type`),
                      INDEX `idx_dashboard_team_perm_team` (`team_id`),
                      CONSTRAINT `fk_dashboard_team_perm_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE,
                      CONSTRAINT `fk_dashboard_team_perm_team` FOREIGN KEY (`team_id`) REFERENCES `is_team` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板团队授权';
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

    private void backfillDashboardAuthorAndPublisher() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'is_dashboard'
                """, Integer.class);
        if (tableCount == null || tableCount == 0) {
            return;
        }
        Integer authorColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'is_dashboard' AND column_name = 'author_user_id'
                """, Integer.class);
        if (authorColumn != null && authorColumn > 0) {
            jdbcTemplate.update("""
                    UPDATE is_dashboard
                    SET author_user_id = owner_user_id
                    WHERE author_user_id IS NULL OR TRIM(author_user_id) = ''
                    """);
            Integer sourceColumn = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'is_dashboard' AND column_name = 'source_dashboard_id'
                    """, Integer.class);
            if (sourceColumn != null && sourceColumn > 0) {
                jdbcTemplate.update("""
                        UPDATE is_dashboard d
                        INNER JOIN is_dashboard s ON s.id = d.source_dashboard_id
                        SET d.author_user_id = COALESCE(NULLIF(TRIM(s.author_user_id), ''), s.owner_user_id)
                        WHERE d.source_dashboard_id IS NOT NULL
                        """);
            }
        }
        Integer publisherColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'is_dashboard' AND column_name = 'publisher_user_id'
                """, Integer.class);
        if (publisherColumn != null && publisherColumn > 0) {
            Integer saveAsColumn = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'is_dashboard' AND column_name = 'save_as_user_id'
                    """, Integer.class);
            if (saveAsColumn != null && saveAsColumn > 0) {
                jdbcTemplate.update("""
                        UPDATE is_dashboard
                        SET save_as_user_id = publisher_user_id
                        WHERE source_dashboard_id IS NOT NULL
                          AND (save_as_user_id IS NULL OR TRIM(save_as_user_id) = '')
                          AND publisher_user_id IS NOT NULL
                          AND TRIM(publisher_user_id) != ''
                        """);
            }
            jdbcTemplate.update("""
                    UPDATE is_dashboard
                    SET publisher_user_id = NULL
                    WHERE status != 'ACTIVE'
                    """);
            jdbcTemplate.update("""
                    UPDATE is_dashboard
                    SET publisher_user_id = owner_user_id
                    WHERE status = 'ACTIVE'
                      AND (publisher_user_id IS NULL OR TRIM(publisher_user_id) = '')
                    """);
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
