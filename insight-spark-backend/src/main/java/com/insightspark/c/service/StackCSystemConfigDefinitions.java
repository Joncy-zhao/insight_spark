package com.insightspark.c.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全栈 C 系统配置模块定义（与前端 stackCConfigSchema.js 对齐）。
 */
public final class StackCSystemConfigDefinitions {

    private StackCSystemConfigDefinitions() {
    }

    public record ConfigDef(
            String key,
            String category,
            String label,
            String valueType,
            String inputType,
            String defaultValue,
            String description,
            String placeholder,
            String optionsJson) {
    }

    public static final List<ConfigDef> ALL = List.of(
            // AI 引擎
            def("ai.text2sql.prompt", "AI", "Text-to-SQL 提示词", "STRING", "textarea", "",
                    "只读展示：推理链路待接入，暂不可在此修改", "在此填写提示词…", null),
            def("ai.llm.provider", "AI", "大模型提供商", "STRING", "select", "openai",
                    "只读展示：来自 Python AI 服务 /ai/models", null, "[\"openai\",\"azure\",\"local\",\"custom\"]"),
            def("ai.llm.model", "AI", "默认模型名称", "STRING", "string", "gpt-4o-mini",
                    "只读展示：来自 Python AI 服务 /ai/models 当前默认模型", "例如 gpt-4o-mini", null),
            def("ai.llm.temperature", "AI", "采样温度", "NUMBER", "number", "0.2",
                    "只读展示：PythonAiService 默认 temperature", null, null),
            def("ai.llm.maxTokens", "AI", "最大 Token", "NUMBER", "number", "4096",
                    "只读展示：来自 Python AI 服务模型配置", null, null),
            def("ai.neo4j.uri", "AI", "Neo4j 知识图谱 URI", "STRING", "string", "bolt://localhost:7687",
                    "GraphRAG 知识图谱连接地址", "bolt://host:7687", null),
            def("ai.neo4j.database", "AI", "Neo4j 数据库", "STRING", "string", "neo4j",
                    "知识图谱库名", "neo4j", null),
            def("ai.llm.routing.gateway", "AI", "大模型路由网关", "STRING", "string", "",
                    "只读展示：application.yml · insight.ai-service-url", "https://gateway.example.com/v1", null),
            def("ai.graphrag.topK", "AI", "GraphRAG 召回 TopK", "NUMBER", "number", "8",
                    "图谱检索返回实体/关系数量上限", null, null),
            def("ai.graphrag.hopDepth", "AI", "GraphRAG 推理跳数", "NUMBER", "number", "2",
                    "多跳推理最大深度", null, null),

            // 安全规则
            def("security.sql.intercept.enabled", "SECURITY", "SQL 拦截开关", "STRING", "boolean", "true",
                    "开启后按规则审计并拦截高风险 SQL", null, null),
            def("security.sql.whitelist", "SECURITY", "SQL 表白名单", "JSON", "stringList", "[]",
                    "全局允许的表名；非空时 SQL 中所有表须在此列表内", null, null),
            def("security.sensitive.fields", "SECURITY", "敏感字段库", "JSON", "sensitiveRules", "[]",
                    "敏感字段规则：关键词、脱敏方式与访问策略", null, null),
            def("security.sensitive.mask.rule", "SECURITY", "脱敏规则", "STRING", "select", "MASK",
                    "敏感字段展示脱敏策略", null, "[\"MASK\",\"HASH\",\"REDACT\"]"),
            def("security.password.minLength", "SECURITY", "密码最小长度", "NUMBER", "number", "8",
                    "用户密码策略：最小长度", null, null),
            def("security.password.requireSpecial", "SECURITY", "要求特殊字符", "STRING", "boolean", "true",
                    "密码须包含特殊字符", null, null),
            def("perf.alert.slow_ms", "SECURITY", "慢查询阈值 (ms)", "NUMBER", "number", "3000",
                    "超过该耗时记为慢查询（与性能治理联动）", null, null),
            def("security.dangerous.ops", "SECURITY", "危险操作拦截", "JSON", "json",
                    "[\"DROP\",\"TRUNCATE\",\"DELETE FROM\"]","拦截关键字 JSON 数组", null, null),
            def("security.cors.origins", "SECURITY", "CORS 允许源", "JSON", "stringList", "[\"http://localhost:5173\"]",
                    "跨域白名单；保存后 WebConfig 动态生效", null, null),
            def("security.api.rate.limit.perMinute", "SECURITY", "接口频率限制", "NUMBER", "number", "120",
                    "单用户每分钟 API 调用上限", null, null),

            // 性能优化
            def("perf.redis.cache.enabled", "PERFORMANCE", "Redis 语义缓存", "STRING", "boolean", "false",
                    "开启后对话/SQL 结果可走语义缓存", null, null),
            def("perf.redis.cache.ttlSeconds", "PERFORMANCE", "缓存 TTL (秒)", "NUMBER", "number", "3600",
                    "语义缓存默认过期时间", null, null),
            def("perf.dashboard.prewarm.enabled", "PERFORMANCE", "看板定时预热", "STRING", "boolean", "false",
                    "按 Cron 预加载热门看板数据", null, null),
            def("perf.dashboard.prewarm.cron", "PERFORMANCE", "预热 Cron 表达式", "STRING", "cron", "0 0 6 * * ?",
                    "看板预热调度 Cron", "0 0 6 * * ?", null),
            def("perf.slow.query.circuit.enabled", "PERFORMANCE", "慢查询熔断", "STRING", "boolean", "false",
                    "连续慢查询触发熔断保护", null, null),
            def("perf.slow.query.circuit.threshold", "PERFORMANCE", "熔断触发次数", "NUMBER", "number", "5",
                    "单位时间内慢查询次数阈值", null, null),
            def("perf.db.pool.maxSize", "PERFORMANCE", "数据库连接池上限", "NUMBER", "number", "20",
                    "应用侧连接池最大连接数", null, null),
            def("perf.batch.task.maxConcurrency", "PERFORMANCE", "批处理最大并发", "NUMBER", "number", "3",
                    "离线批处理任务并发上限", null, null),
            def("perf.batch.task.timeoutSeconds", "PERFORMANCE", "批处理超时 (秒)", "NUMBER", "number", "600",
                    "单批任务最长执行时间", null, null),
            def("perf.alert.cpu.percent", "PERFORMANCE", "CPU 告警阈值 (%)", "NUMBER", "number", "90",
                    "系统负载估算 CPU 超阈告警", null, null),
            def("perf.alert.queryTimeoutMs", "PERFORMANCE", "查询响应超时 (ms)", "NUMBER", "number", "30000",
                    "超过该耗时触发性能告警", null, null),
            def("perf.db.query.maxConcurrent", "PERFORMANCE", "单用户最大并发查询", "NUMBER", "number", "4",
                    "限制单用户同时执行的 SQL 数量", null, null),
            def("perf.db.access.maxPerMinute", "PERFORMANCE", "数据库访问频次/分钟", "NUMBER", "number", "120",
                    "单用户每分钟 SQL 调用上限", null, null),
            def("perf.resource.priority.text2sql", "PERFORMANCE", "Text-to-SQL 资源优先级", "NUMBER", "number", "90",
                    "1~100，越高越优先保障", null, null),
            def("perf.resource.priority.graphrag", "PERFORMANCE", "GraphRAG 资源优先级", "NUMBER", "number", "85",
                    "1~100，越高越优先保障", null, null),
            def("perf.resource.priority.upload", "PERFORMANCE", "上传批处理优先级", "NUMBER", "number", "40",
                    "1~100，越高越优先保障", null, null),
            def("perf.resource.priority.dashboard", "PERFORMANCE", "看板渲染优先级", "NUMBER", "number", "60",
                    "1~100，越高越优先保障", null, null),

            // 文件上传
            def("upload.max.fileSizeMb", "UPLOAD", "单文件大小上限 (MB)", "NUMBER", "number", "50",
                    "Excel/CSV 上传大小限制", null, null),
            def("upload.allowed.formats", "UPLOAD", "允许格式", "JSON", "stringList", "[\"xlsx\",\"xls\",\"csv\"]",
                    "允许上传的文件扩展名（不含点）", null, null),
            def("upload.parse.rules", "UPLOAD", "解析规则", "JSON", "json", "{}",
                    "表头识别、编码等解析规则 JSON", "{}", null),
            def("upload.storage.type", "UPLOAD", "存储类型", "STRING", "select", "local",
                    "文件存储后端", null, "[\"local\",\"oss\",\"s3\"]"),
            def("upload.storage.path", "UPLOAD", "存储路径", "STRING", "string", "./uploads",
                    "本地或对象存储根路径", "./uploads", null),
            def("upload.permission.roles", "UPLOAD", "上传权限角色", "JSON", "roleMulti", "[\"USER\",\"ADMIN\"]",
                    "允许上传的角色", null, null),
            def("upload.dedup.enabled", "UPLOAD", "去重开关", "STRING", "boolean", "true",
                    "相同文件指纹去重", null, null),
            def("upload.dedup.ttlDays", "UPLOAD", "去重有效期 (天)", "NUMBER", "number", "30",
                    "去重记录保留天数", null, null),
            def("upload.parse.timeoutSeconds", "UPLOAD", "解析超时 (秒)", "NUMBER", "number", "120",
                    "文件解析最长等待时间", null, null),

            // 数据源
            def("datasource.pool.minIdle", "DATASOURCE", "连接池最小空闲", "NUMBER", "number", "2",
                    "官方数据源连接池 minIdle", null, null),
            def("datasource.pool.maxActive", "DATASOURCE", "连接池最大活跃", "NUMBER", "number", "10",
                    "官方数据源连接池 maxActive", null, null),
            def("datasource.connect.timeoutMs", "DATASOURCE", "连接超时 (ms)", "NUMBER", "number", "10000",
                    "建立连接超时", null, null),
            def("datasource.reconnect.maxRetries", "DATASOURCE", "超时重连次数", "NUMBER", "number", "3",
                    "连接失败后最大重试次数", null, null),
            def("datasource.heartbeat.intervalSeconds", "DATASOURCE", "心跳检测间隔 (秒)", "NUMBER", "number", "60",
                    "数据源心跳探测周期", null, null),
            def("datasource.federated.enabled", "DATASOURCE", "联邦跨库", "STRING", "boolean", "false",
                    "启用联邦查询跨库关联", null, null),
            def("datasource.federated.maxJoinTables", "DATASOURCE", "联邦最大关联表数", "NUMBER", "number", "4",
                    "跨库 JOIN 允许的最大表数", null, null),
            def("datasource.access.rate.limit.perMinute", "DATASOURCE", "数据源访问频次", "NUMBER", "number", "60",
                    "单用户每分钟数据源查询上限", null, null),

            // 交互
            def("interaction.sse.timeoutSeconds", "INTERACTION", "SSE 流式超时 (秒)", "NUMBER", "number", "120",
                    "对话流式输出最长等待", null, null),
            def("interaction.chart.defaultTheme", "INTERACTION", "图表默认主题", "STRING", "select", "light",
                    "ECharts 默认主题", null, "[\"light\",\"dark\",\"auto\"]"),
            def("interaction.chart.animation", "INTERACTION", "图表动画", "STRING", "boolean", "true",
                    "全局图表动画开关", null, null),
            def("interaction.chat.maxTurns", "INTERACTION", "对话最大轮次", "NUMBER", "number", "50",
                    "单会话保留的最大轮次数", null, null),
            def("interaction.chat.historyRetentionDays", "INTERACTION", "对话历史保留 (天)", "NUMBER", "number", "90",
                    "历史对话清理周期", null, null),
            def("interaction.frontend.lazyLoad", "INTERACTION", "前端懒加载", "STRING", "boolean", "true",
                    "看板/图表按需懒加载", null, null),
            def("interaction.voice.enabled", "INTERACTION", "语音播报", "STRING", "boolean", "false",
                    "启用 TTS 语音播报", null, null),
            def("interaction.voice.defaultLang", "INTERACTION", "语音语言", "STRING", "select", "zh-CN",
                    "语音播报默认语言", null, "[\"zh-CN\",\"en-US\"]"),

            // 通知
            def("notify.alert.push.enabled", "NOTIFICATION", "预警推送", "STRING", "boolean", "true",
                    "启用业务预警推送", null, null),
            def("notify.alert.channels", "NOTIFICATION", "推送渠道", "JSON", "channelMulti", "[\"email\",\"dingtalk\"]",
                    "已启用的预警推送渠道", null, null),
            def("notify.announcement.autoExpireDays", "NOTIFICATION", "公告自动过期 (天)", "NUMBER", "number", "0",
                    "0 表示不过期", null, null),
            def("notify.announcement.maxPinned", "NOTIFICATION", "最大置顶数", "NUMBER", "number", "3",
                    "同时置顶公告数量上限", null, null),
            def("notify.anomaly.enabled", "NOTIFICATION", "异常告警", "STRING", "boolean", "true",
                    "定时预警 Agent 是否运行", null, null),
            def("notify.anomaly.recipients", "NOTIFICATION", "告警接收人", "JSON", "userMulti", "[]",
                    "接收异常告警的用户 user_id 列表", null, null),
            def("notify.system.update.enabled", "NOTIFICATION", "系统更新通知", "STRING", "boolean", "true",
                    "版本更新/维护通知开关", null, null)
    );

    public static final Map<String, String> MODULE_TITLES = Map.of(
            "AI", "AI 引擎配置",
            "SECURITY", "安全规则配置",
            "PERFORMANCE", "性能优化配置",
            "UPLOAD", "文件上传配置",
            "DATASOURCE", "数据源配置",
            "INTERACTION", "交互配置",
            "NOTIFICATION", "通知配置"
    );

    public static final List<String> MODULE_ORDER = List.of(
            "AI", "SECURITY", "PERFORMANCE", "UPLOAD", "DATASOURCE", "INTERACTION", "NOTIFICATION"
    );

    private static ConfigDef def(String key, String category, String label, String valueType, String inputType,
                                 String defaultValue, String description, String placeholder, String optionsJson) {
        return new ConfigDef(key, category, label, valueType, inputType, defaultValue, description, placeholder, optionsJson);
    }

    public static Map<String, Object> toSchemaItem(ConfigDef def, String currentValue, Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("configKey", def.key());
        item.put("category", def.category());
        item.put("label", def.label());
        item.put("valueType", def.valueType());
        item.put("inputType", def.inputType());
        item.put("defaultValue", def.defaultValue());
        item.put("description", def.description());
        item.put("placeholder", def.placeholder());
        item.put("options", def.optionsJson());
        item.put("configValue", currentValue != null ? currentValue : def.defaultValue());
        if (row != null) {
            item.put("updatedAt", row.get("updatedAt"));
            item.put("updatedBy", row.get("updatedBy"));
        }
        return item;
    }
}
