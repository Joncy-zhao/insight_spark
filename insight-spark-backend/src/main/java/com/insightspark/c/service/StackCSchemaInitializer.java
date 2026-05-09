package com.insightspark.c.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全栈 C 表结构与 sql/insight_spark_schema_stack_c.sql 保持一致，启动时自动 CREATE TABLE IF NOT EXISTS，
 * 行为与同项目中 SqlAuditService、AuthService 等一致。
 */
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
                      `audience` VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '受众：ALL 全员 / USER 普通用户 / ADMIN 管理员',
                      `pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶：1-置顶',
                      `priority` INT NOT NULL DEFAULT 0 COMMENT '排序优先级，数值越大越靠前',
                      `publish_status` VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态：DRAFT 草稿 / PUBLISHED 已发布',
                      `published_at` DATETIME NULL COMMENT '发布时间',
                      `expire_at` DATETIME NULL COMMENT '过期时间，空表示长期有效',
                      `created_by` VARCHAR(64) NULL COMMENT '发布人 user_id，关联 is_user.user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_announcement_audience_published` (`audience`, `publish_status`, `published_at`),
                      INDEX `idx_announcement_expire` (`expire_at`),
                      CONSTRAINT `fk_announcement_creator` FOREIGN KEY (`created_by`) REFERENCES `is_user` (`user_id`)
                        ON DELETE SET NULL ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统公告（工作台/管理员公告）';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `owner_user_id` VARCHAR(64) NOT NULL COMMENT '所有者 user_id，关联 is_user.user_id',
                      `name` VARCHAR(255) NOT NULL COMMENT '看板名称',
                      `description` VARCHAR(1000) NULL COMMENT '看板描述',
                      `layout_json` LONGTEXT NOT NULL COMMENT '画布与组件布局、ECharts 配置等 JSON',
                      `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否企业公共看板：1-公共，0-个人',
                      `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 使用中 / ARCHIVED 归档 / DISABLED 停用',
                      `share_token` VARCHAR(64) NULL COMMENT '分享链接 token，空表示未开启分享',
                      `share_expire_at` DATETIME NULL COMMENT '分享过期时间',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_dashboard_share_token` (`share_token`),
                      INDEX `idx_dashboard_owner` (`owner_user_id`),
                      INDEX `idx_dashboard_public_status` (`is_public`, `status`),
                      CONSTRAINT `fk_dashboard_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据看板（个人/公共，布局 JSON）';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_dashboard_component` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '看板内组件主键（对应 layout_json items[].i）',
                      `dashboard_id` BIGINT NOT NULL COMMENT '看板 id',
                      `chart_id` BIGINT NOT NULL COMMENT 'B 端对话查询历史 is_chat_query_history.id',
                      `position_config` VARCHAR(512) NOT NULL DEFAULT '{"x":0,"y":0,"w":6,"h":4}' COMMENT '位姿 JSON 冗余',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (`id`),
                      KEY `idx_dashboard_component_board` (`dashboard_id`),
                      KEY `idx_dashboard_component_chart` (`chart_id`),
                      CONSTRAINT `fk_dashboard_component_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
                        ON DELETE CASCADE ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板与对话图表关联（钉入）';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_annotation` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '作者 user_id，关联 is_user.user_id',
                      `target_type` VARCHAR(32) NOT NULL COMMENT '对象类型：DASHBOARD 看板 / CHART 图表快照 / QUERY 对话查询等',
                      `target_id` BIGINT NOT NULL COMMENT '对象主键，含义随 target_type 变化（逻辑关联，多态）',
                      `dashboard_id` BIGINT NULL COMMENT '所属看板 id，关联 is_dashboard.id，可空',
                      `bind_json` JSON NULL COMMENT '绑定维度、指标、时间等上下文 JSON',
                      `content` TEXT NOT NULL COMMENT '批注正文',
                      `tag` VARCHAR(64) NULL COMMENT '批注标签，如异常说明、经验总结',
                      `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：1-已删除',
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务批注（多态绑定数据节点）';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_comment` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `parent_id` BIGINT NULL COMMENT '父评论 id，空表示顶层评论',
                      `user_id` VARCHAR(64) NOT NULL COMMENT '作者 user_id，关联 is_user.user_id',
                      `target_type` VARCHAR(32) NOT NULL COMMENT '评论挂载对象类型，与批注/看板等业务一致',
                      `target_id` BIGINT NOT NULL COMMENT '挂载对象主键（逻辑关联，多态）',
                      `content` TEXT NOT NULL COMMENT '评论内容',
                      `mentions_json` JSON NULL COMMENT '@提醒的用户 user_id 列表等 JSON',
                      `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：1-已删除',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_comment_parent` (`parent_id`),
                      INDEX `idx_comment_user` (`user_id`),
                      INDEX `idx_comment_target` (`target_type`, `target_id`),
                      CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
                        ON DELETE RESTRICT ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协同评论（多态挂载，支持回复与@）';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_system_config` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `config_key` VARCHAR(128) NOT NULL COMMENT '配置键，全局唯一',
                      `config_value` LONGTEXT NULL COMMENT '配置值',
                      `value_type` VARCHAR(32) NOT NULL DEFAULT 'STRING' COMMENT '值类型：STRING / JSON / NUMBER 等',
                      `category` VARCHAR(64) NULL COMMENT '配置分组：AI / SECURITY / UI 等',
                      `description` VARCHAR(512) NULL COMMENT '配置说明',
                      `updated_by` VARCHAR(64) NULL COMMENT '最后修改人 user_id，关联 is_user.user_id',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                      `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `uk_system_config_key` (`config_key`),
                      INDEX `idx_system_config_category` (`category`),
                      CONSTRAINT `fk_system_config_updater` FOREIGN KEY (`updated_by`) REFERENCES `is_user` (`user_id`)
                        ON DELETE SET NULL ON UPDATE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统全局配置键值表';
                    """);

            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS `is_perf_intervention` (
                      `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                      `audit_log_id` BIGINT NOT NULL COMMENT '关联 is_sql_audit_log.id',
                      `action` VARCHAR(32) NOT NULL COMMENT '处置动作：ACK 已标记等（非杀数据库线程）',
                      `operator_user_id` VARCHAR(64) NOT NULL COMMENT '操作人 user_id',
                      `remark` VARCHAR(500) NULL COMMENT '备注',
                      `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
                      PRIMARY KEY (`id`),
                      INDEX `idx_perf_intervention_audit` (`audit_log_id`),
                      INDEX `idx_perf_intervention_created` (`created_at`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='性能治理-慢查询处置记录';
                    """);
        } catch (Exception e) {
            log.error("全栈 C 表初始化失败（可检查 is_user 是否已创建、MySQL 版本是否支持 JSON 类型）", e);
            throw e;
        }
    }
}
