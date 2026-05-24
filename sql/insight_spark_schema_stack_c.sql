-- insight_spark_schema_stack_c.sql
-- 鍏ㄦ爤 C锛氱湅鏉裤€佹壒娉ㄥ崗鍚屻€佸伐浣滃彴鍏憡銆佺郴缁熼厤缃€佹€ц兘娌荤悊澶勭疆琛?
-- 璇存槑锛?
--   0) Spring Boot 鍚姩鏃?com.insightspark.c.service.StackCSchemaInitializer 浼氭墽琛岀瓑浠?DDL锛堜笌涓嬫枃涓€鑷达級銆?
--   1) 璇峰嬁淇敼鍏ㄦ爤 A/B 鍦?insight_spark_schema_from_repo.sql 涓殑鏃㈡湁琛ㄥ畾涔夈€?
--   2) 鏈枃浠朵粎鏂板涓嬪垪琛紱鎵嬪姩鎵ц鍓嶈纭繚宸插瓨鍦ㄥ簱 insight_spark 涓?is_user 绛?A 渚ц〃宸插垱寤恒€?
--   3) 澶栭敭鎸囧悜 is_user.user_id銆乮s_dashboard.id锛涜嫢鐜涓嶄究浣跨敤澶栭敭锛屽彲鍒犻櫎鍚勮〃鏈熬 CONSTRAINT 娈点€?
--   4) is_perf_intervention 渚濊禆 A 渚?is_sql_audit_log 宸插瓨鍦紱鎬ц兘椤点€屾爣璁板缃€嶄細鍐欏叆璇ヨ〃銆?

USE `insight_spark`;

-- =========================================================
-- 鍏ㄦ爤 C 路 鏍稿績琛紙绗竴鎵癸級
-- =========================================================

-- --------------------------------------------------------
-- C.1 绯荤粺鍏憡锛堝伐浣滃彴鍏憡鍖猴級
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_system_announcement` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `title` VARCHAR(255) NOT NULL COMMENT '鍏憡鏍囬',
  `content` LONGTEXT NOT NULL COMMENT '鍏憡姝ｆ枃',
  `audience` VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '鍙椾紬锛欰LL 鍏ㄥ憳 / USER 鏅€氱敤鎴?/ ADMIN 绠＄悊鍛?,
  `pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '鏄惁缃《锛?-缃《',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '鎺掑簭浼樺厛绾э紝鏁板€艰秺澶ц秺闈犲墠',
  `publish_status` VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' COMMENT '鍙戝竷鐘舵€侊細DRAFT 鑽夌 / PUBLISHED 宸插彂甯?,
  `published_at` DATETIME NULL COMMENT '鍙戝竷鏃堕棿',
  `expire_at` DATETIME NULL COMMENT '杩囨湡鏃堕棿锛岀┖琛ㄧず闀挎湡鏈夋晥',
  `created_by` VARCHAR(64) NULL COMMENT '鍙戝竷浜?user_id锛屽叧鑱?is_user.user_id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  INDEX `idx_announcement_audience_published` (`audience`, `publish_status`, `published_at`),
  INDEX `idx_announcement_expire` (`expire_at`),
  CONSTRAINT `fk_announcement_creator` FOREIGN KEY (`created_by`) REFERENCES `is_user` (`user_id`)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绯荤粺鍏憡锛堝伐浣滃彴/绠＄悊鍛樺叕鍛婏級';


-- --------------------------------------------------------
-- C.2 鎴戠殑鐪嬫澘 / 鍏叡鐪嬫澘锛堢敾甯?JSON锛屽悗缁彲鎵╁睍鍒嗕韩瀛楁锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_dashboard` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '鎵€鏈夎€?user_id锛屽叧鑱?is_user.user_id',
  `name` VARCHAR(255) NOT NULL COMMENT '鐪嬫澘鍚嶇О',
  `description` VARCHAR(1000) NULL COMMENT '鐪嬫澘鎻忚堪',
  `layout_json` LONGTEXT NOT NULL COMMENT '鐢诲竷涓庣粍浠跺竷灞€銆丒Charts 閰嶇疆绛?JSON',
  `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '鏄惁浼佷笟鍏叡鐪嬫澘锛?-鍏叡锛?-涓汉',
  `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '鐘舵€侊細ACTIVE 浣跨敤涓?/ ARCHIVED 褰掓。 / DISABLED 鍋滅敤',
  `share_token` VARCHAR(64) NULL COMMENT '鍒嗕韩閾炬帴 token锛岀┖琛ㄧず鏈紑鍚垎浜?,
  `share_expire_at` DATETIME NULL COMMENT '鍒嗕韩杩囨湡鏃堕棿',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dashboard_share_token` (`share_token`),
  INDEX `idx_dashboard_owner` (`owner_user_id`),
  INDEX `idx_dashboard_public_status` (`is_public`, `status`),
  CONSTRAINT `fk_dashboard_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `is_user` (`user_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鏁版嵁鐪嬫澘锛堜釜浜?鍏叡锛屽竷灞€ JSON锛?;


-- --------------------------------------------------------
-- C.2b 鐪嬫澘缁勪欢锛堥拤鍏ョ殑瀵硅瘽鍥捐〃锛屽叧鑱?B 绔?is_chat_query_history锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_dashboard_component` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭锛屽搴?layout_json items[].i',
  `dashboard_id` BIGINT NOT NULL COMMENT '鐪嬫澘 id',
  `chart_id` BIGINT NOT NULL COMMENT 'is_chat_query_history.id',
  `artifact_id` BIGINT NULL COMMENT 'is_chat_conversation_artifact.id',
  `turn_id` BIGINT NULL COMMENT 'is_chat_conversation_turn.id',
  `position_config` VARCHAR(512) NOT NULL DEFAULT '{"x":0,"y":0,"w":6,"h":4}' COMMENT '浣嶅Э JSON 鍐椾綑',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dashboard_component_board` (`dashboard_id`),
  KEY `idx_dashboard_component_chart` (`chart_id`),
  KEY `idx_dashboard_component_artifact` (`artifact_id`),
  KEY `idx_dashboard_component_turn` (`turn_id`),
  CONSTRAINT `fk_dashboard_component_board` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鐪嬫澘涓庡璇濆浘琛ㄥ叧鑱?;


-- --------------------------------------------------------
-- C.3 涓氬姟鎵规敞锛堢粦瀹氬浘琛?鐪嬫澘/鏌ヨ绛変笟鍔″璞★紝澶氭€?target锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_annotation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `user_id` VARCHAR(64) NOT NULL COMMENT '浣滆€?user_id锛屽叧鑱?is_user.user_id',
  `target_type` VARCHAR(32) NOT NULL COMMENT '瀵硅薄绫诲瀷锛欴ASHBOARD 鐪嬫澘 / CHART 鍥捐〃蹇収 / QUERY 瀵硅瘽鏌ヨ绛?,
  `target_id` BIGINT NOT NULL COMMENT '瀵硅薄涓婚敭锛屽惈涔夐殢 target_type 鍙樺寲锛堥€昏緫鍏宠仈锛屽鎬侊級',
  `dashboard_id` BIGINT NULL COMMENT '鎵€灞炵湅鏉?id锛屽叧鑱?is_dashboard.id锛屽彲绌?,
  `bind_json` JSON NULL COMMENT '缁戝畾缁村害銆佹寚鏍囥€佹椂闂寸瓑涓婁笅鏂?JSON',
  `content` TEXT NOT NULL COMMENT '鎵规敞姝ｆ枃',
  `tag` VARCHAR(64) NULL COMMENT '鎵规敞鏍囩锛屽寮傚父璇存槑銆佺粡楠屾€荤粨',
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '閫昏緫鍒犻櫎锛?-宸插垹闄?,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  INDEX `idx_annotation_user` (`user_id`),
  INDEX `idx_annotation_target` (`target_type`, `target_id`),
  INDEX `idx_annotation_dashboard` (`dashboard_id`),
  CONSTRAINT `fk_annotation_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_annotation_dashboard` FOREIGN KEY (`dashboard_id`) REFERENCES `is_dashboard` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='涓氬姟鎵规敞锛堝鎬佺粦瀹氭暟鎹妭鐐癸級';


-- --------------------------------------------------------
-- C.4 鍗忓悓璇勮锛堟ゼ涓ゼ锛屾敮鎸?@ 鍒楄〃 JSON锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_comment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `parent_id` BIGINT NULL COMMENT '鐖惰瘎璁?id锛岀┖琛ㄧず椤跺眰璇勮',
  `user_id` VARCHAR(64) NOT NULL COMMENT '浣滆€?user_id锛屽叧鑱?is_user.user_id',
  `target_type` VARCHAR(32) NOT NULL COMMENT '璇勮鎸傝浇瀵硅薄绫诲瀷锛屼笌鎵规敞/鐪嬫澘绛変笟鍔′竴鑷?,
  `target_id` BIGINT NOT NULL COMMENT '鎸傝浇瀵硅薄涓婚敭锛堥€昏緫鍏宠仈锛屽鎬侊級',
  `content` TEXT NOT NULL COMMENT '璇勮鍐呭',
  `mentions_json` JSON NULL COMMENT '@鎻愰啋鐨勭敤鎴?user_id 鍒楄〃绛?JSON',
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '閫昏緫鍒犻櫎锛?-宸插垹闄?,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  INDEX `idx_comment_parent` (`parent_id`),
  INDEX `idx_comment_user` (`user_id`),
  INDEX `idx_comment_target` (`target_type`, `target_id`),
  CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `is_user` (`user_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鍗忓悓璇勮锛堝鎬佹寕杞斤紝鏀寔鍥炲涓嶡锛?;


-- --------------------------------------------------------
-- C.5 绯荤粺閰嶇疆锛堝叏灞€ KV锛屽悗缁€ц兘/浜や簰绛夊彲杩佸叆姝よ〃锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_system_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `config_key` VARCHAR(128) NOT NULL COMMENT '閰嶇疆閿紝鍏ㄥ眬鍞竴',
  `config_value` LONGTEXT NULL COMMENT '閰嶇疆鍊?,
  `value_type` VARCHAR(32) NOT NULL DEFAULT 'STRING' COMMENT '鍊肩被鍨嬶細STRING / JSON / NUMBER 绛?,
  `category` VARCHAR(64) NULL COMMENT '閰嶇疆鍒嗙粍锛欰I / SECURITY / UI 绛?,
  `description` VARCHAR(512) NULL COMMENT '閰嶇疆璇存槑',
  `updated_by` VARCHAR(64) NULL COMMENT '鏈€鍚庝慨鏀逛汉 user_id锛屽叧鑱?is_user.user_id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_config_key` (`config_key`),
  INDEX `idx_system_config_category` (`category`),
  CONSTRAINT `fk_system_config_updater` FOREIGN KEY (`updated_by`) REFERENCES `is_user` (`user_id`)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绯荤粺鍏ㄥ眬閰嶇疆閿€艰〃';


-- =========================================================
-- 鍏ㄦ爤 C 路 鎬ц兘娌荤悊涓績锛堢鐞嗗憳绔紝鎱㈡煡璇㈠缃褰曪級
-- =========================================================

-- --------------------------------------------------------
-- C.6 鎬ц兘娌荤悊 - 鎱㈡煡璇笟鍔″缃紙涓?is_sql_audit_log.id 閫昏緫鍏宠仈锛?
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `is_perf_intervention` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `audit_log_id` BIGINT NOT NULL COMMENT '鍏宠仈 is_sql_audit_log.id',
  `action` VARCHAR(32) NOT NULL COMMENT '澶勭疆鍔ㄤ綔锛欰CK 宸叉爣璁扮瓑锛堥潪鏉€鏁版嵁搴撶嚎绋嬶級',
  `operator_user_id` VARCHAR(64) NOT NULL COMMENT '鎿嶄綔浜?user_id',
  `remark` VARCHAR(500) NULL COMMENT '澶囨敞',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '璁板綍鏃堕棿',
  PRIMARY KEY (`id`),
  INDEX `idx_perf_intervention_audit` (`audit_log_id`),
  INDEX `idx_perf_intervention_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鎬ц兘娌荤悊-鎱㈡煡璇㈠缃褰?;
