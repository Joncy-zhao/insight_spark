# 对话查询改造建议

## 1. 现状判断
当前实现更像“单次问句 -> 单条历史 -> 单张图表”的流水线，而不是连续对话：
- `is_chat_query_history` 把问句、SQL、图表快照、审计信息压在一条记录里。
- `ChatBiService.executeChat(question, tableName)` 只接受单轮输入。
- `ChatController` 的 `/api/chat/ask`、`/api/chat/ask-stream` 没有 `sessionId` / `parentTurnId`。
- 前端的“最近查询”也是按单条记录展示，缺少会话维度。

这会导致以下问题：
- 不能承接上文，追问时要重复说清范围。
- 不能分支对话，例如“继续看 2025 年”和“换成华南地区”。
- 不能表达一次对话里的多种产物，比如文字结论、表格、SQL、图表、解释。
- 不能自然支持“先看大盘，再追问明细，再回到原因分析”的真实 BI 习惯。

## 1.1 不影响旧功能的原则
这次优化建议按“先兼容、再替换、最后拆分”的顺序做，默认不破坏现有链路：
- 旧接口先保留，`/api/chat/ask`、`/api/chat/ask-stream`、`/api/chat/ask-enhanced` 不直接下线。
- 旧表先兼容，`is_chat_query_history` 继续可读可写，不要求一次性切到新表。
- 旧前端先能跑，`最近查询`、`复制 SQL`、`钉入看板`、`生成诊断` 这些能力不改行为。
- 新能力默认灰度，先以“可选会话模式”进入，不强制所有入口立刻升级。
- 数据迁移只做增量字段或新表，不做破坏性重命名，不回填影响线上查询的关键列。

## 2. 推荐目标模型
建议改成三层：
1. 会话 Session：一次连续对话的容器。
2. 轮次 Turn：用户问句与助手回复的每一轮。
3. 产物 Artifact：SQL、图表、表格、解释、诊断报告等可独立引用的结果。

### 推荐表
```sql
is_chat_conversation
is_chat_conversation_turn
is_chat_conversation_artifact
```

### 建议字段
`is_chat_conversation`
- `id`
- `user_id`
- `title`
- `datasource_id` / `scope_json`
- `business_model_id`
- `summary`
- `last_turn_id`
- `status`
- `created_at`
- `updated_at`

`is_chat_conversation_turn`
- `id`
- `conversation_id`
- `parent_turn_id`
- `turn_no`
- `role`：USER / ASSISTANT / SYSTEM
- `message_text`
- `intent_type`
- `context_json`
- `followup_mode`
- `created_at`

`is_chat_conversation_artifact`
- `id`
- `turn_id`
- `artifact_type`：SQL / CHART / TABLE / TEXT / REPORT
- `artifact_json`
- `sql_text`
- `chart_type`
- `risk_level`
- `created_at`

## 3. 兼容现有实现的最小改造
如果不想一次性重构，先在 `is_chat_query_history` 上补能力：
- 增加 `conversation_id`
- 增加 `parent_history_id`
- 增加 `turn_no`
- 增加 `message_role`
- 增加 `intent_type`
- 增加 `context_json`
- 增加 `scope_json`
- 增加 `artifact_type`
- 增加 `summary_text`

这样可以先把现有表从“单条记录”升级成“会话扁平表”，再逐步拆分成 session/turn/artifact 三层。

## 4. 接口建议
把接口从“问一次就返回一次”改成“围绕会话工作”：

- `POST /api/chat/sessions`：创建会话
- `GET /api/chat/sessions`：会话列表
- `GET /api/chat/sessions/{id}`：会话详情
- `GET /api/chat/sessions/{id}/turns`：轮次列表
- `POST /api/chat/sessions/{id}/messages`：发送一轮消息
- `POST /api/chat/sessions/{id}/messages/stream`：流式发送
- `POST /api/chat/sessions/{id}/summary`：生成/刷新摘要

兼容层可以保留：
- `/api/chat/ask`
- `/api/chat/ask-stream`
- `/api/chat/ask-enhanced`

但它们内部要自动创建或复用一个默认会话。

## 5. 后端处理链路
建议把 `ChatBiService.executeChat` 改成接收请求对象，而不是只收 `question/tableName`：

```java
ChatQueryRequest {
  Long conversationId;
  Long parentTurnId;
  List<String> tableNames;
  Map<String, Object> filters;
  String question;
  String mode;
}
```

处理顺序建议是：
1. 读取当前会话摘要。
2. 加载最近 N 轮上下文。
3. 结合用户本轮问题识别追问、改口径、对比、解释、明细查询等意图。
4. 必要时先返回澄清问题，而不是直接生成 SQL。
5. 生成 SQL / 图表 / 文本说明后，分别写入 artifact。
6. 回写会话摘要、最后一轮、标签和检索信息。

## 6. 更贴近真实场景的能力点
建议重点补这几类场景：
- 追问：`再看华东地区`、`按去年同期对比`
- 改口径：`只看近 30 天`、`去掉退款订单`
- 明细下钻：先看汇总，再点到门店/客户/订单
- 解释型问答：`为什么本月下滑`
- 多结果输出：表格 + 图表 + 一句话结论
- 多表/多源分析：一个会话里可能跨多个表
- 分支对话：保留原问题，另起一支新分析
- 协同引用：让看板、批注、诊断报告引用具体 turn 或 artifact，而不是只认一条 history

## 7. 前端建议
前端不要只保留“最近查询”，而要增加会话工作区：
- 左侧会话列表
- 中间轮次时间线
- 顶部展示当前数据源、业务模型、分析范围
- 每轮回复支持“继续追问”“复制 SQL”“钉入看板”“生成诊断”
- 搜索从“问句关键词”升级为“会话标题 + 最近问句 + 标签”

## 8. 与现有表的关系
当前仓库里：
- `sql/insight_spark_schema_from_repo.sql` 的 `is_chat_query_history`
- `insight-spark-backend/src/main/java/com/insightspark/service/ChatQueryHistoryService.java`
- `insight-spark-backend/src/main/java/com/insightspark/controller/ChatController.java`
- `insight-spark-frontend/src/views/user/ChatAnalysisView.vue`

这些都默认一条历史记录对应一次完整分析。改成会话模型后，`is_dashboard_component.chart_id`、历史回溯、批注绑定都要指向具体 `artifact_id` 或 `turn_id`，不要再只绑定“整次查询”。

## 9. 推荐迁移顺序
1. 先给 `is_chat_query_history` 增加会话相关字段，保证旧功能不坏。
2. 再把 `/api/chat/*` 改成支持 `conversationId`。
3. 再把前端从“最近查询”切到“会话列表 + 轮次列表”。
4. 最后拆分出独立的 `conversation / turn / artifact` 三张表。
5. 上线时先双写或兼容读一段时间，确认旧入口和新入口结果一致后再逐步收口。

## 10. 结论
如果目标是“更贴合现实大多数方案的对话查询”，核心不是再多加几个字段，而是把系统从“单次查询记录”升级成“可追问、可分支、可沉淀上下文的会话式 BI”。这样才能真正支撑真实业务里最常见的分析流程。
