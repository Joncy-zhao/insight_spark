# 析数灵犀 Insight Spark

析数灵犀是一个 AI 驱动的对话式智能 BI 系统，面向“上传数据、自然语言提问、自动生成图表、沉淀业务知识、治理权限与审计”的完整分析流程。

项目由三个主要服务组成：

| 目录 | 说明 |
| --- | --- |
| `insight-spark-frontend` | Vue 3 + Vite + Element Plus + ECharts 前端，提供用户端与管理员端页面 |
| `insight-spark-backend` | Spring Boot 后端，负责认证、数据上传、SQL 审计、权限控制、看板、诊断报告和系统配置 |
| `insight-spark-ai-service` | Python FastAPI AI 服务，负责 Text-to-SQL、图表推荐、业务语义建模、智能诊断、语音相关接口 |

## 核心功能

- 数据上传：支持 Excel/CSV 上传、字段语义识别、数据预览、质量检查和清洗辅助。
- 对话查询：通过自然语言生成 SQL，经权限校验和 SQL 审计后返回表格或 ECharts 图表。
- 智能看板：支持个人/公共看板、组件布局、图表快照、图片/文本/视频/轮播组件。
- 业务协同：支持看板批注、评论、团队授权、经验沉淀和协作汇报。
- 智能诊断：结合业务数据、知识文档和知识图谱生成归因报告，支持导出。
- 高级分析：支持预测、What-if 推演、预警规则、预警事件和推送记录管理。
- 管理后台：包含数据源管理、用户与权限、SQL 安全审计、AI 图表规则、性能治理、系统配置等模块。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Element Plus、ECharts、Axios、grid-layout-plus |
| 后端 | Java 17、Spring Boot 3、MyBatis-Plus、MySQL、HikariCP、EasyExcel、PDFBox、Apache POI、JSqlParser |
| AI 服务 | Python、FastAPI、Uvicorn、Pydantic、pandas、scikit-learn、DashScope/OpenAI 兼容接口 |
| 数据与知识 | MySQL、Neo4j、Redis 可选缓存 |

## 本地环境准备

请先确认本机已安装并启动以下依赖：

- JDK 17
- Maven
- Node.js 与 npm
- Python 3.10+，并可执行 `python -m pip`
- MySQL 8.x，创建数据库 `insight_spark`
- Neo4j 5.x，用于知识图谱、GraphRAG 和诊断报告能力
- Redis 可选，用于语义缓存和预测缓存；未启动时系统会回落到本地逻辑

创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS insight_spark
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

后端配置参考：

```text
insight-spark-backend/src/main/resources/application-example.yml
```

本地运行时请根据本机 MySQL、Neo4j、Redis 和推送通道配置维护：

```text
insight-spark-backend/src/main/resources/application.yml
```

AI 服务如需调用云端或本地大模型，复制并填写：

```powershell
Copy-Item .\insight-spark-ai-service\.env.example .\insight-spark-ai-service\.env
```

常用模型配置项：

```text
OPENAI_API_KEY
OPENAI_MODEL
OPENAI_BASE_URL
LOCAL_MODEL
LOCAL_BASE_URL
DASHSCOPE_API_KEY
```

未配置大模型密钥时，AI 服务仍保留部分规则兜底能力，但 Text-to-SQL、诊断解释、语音等效果会受限。

## 推荐启动方式

首次运行先安装依赖：

```powershell
cd .\insight-spark-ai-service
python -m pip install -r requirements.txt

cd ..\insight-spark-frontend
npm install
```

确认 MySQL、Neo4j 已启动后，在项目根目录执行：

```powershell
cd D:\siyouyun\homework\syy
.\start-all.ps1
```

脚本会依次启动：

| 服务 | 端口 | 健康检查/访问地址 |
| --- | --- | --- |
| AI 服务 | 8000 | `http://localhost:8000/health` |
| 后端服务 | 8080 | `http://localhost:8080/api/data/tables` |
| 前端页面 | 5173 | `http://localhost:5173` |

日志位置：

```text
.run/logs/
```

停止所有由脚本启动的服务：

```powershell
.\stop-all.ps1
```

如果只想先跑后端和前端，可以跳过 AI 服务：

```powershell
.\start-all.ps1 -SkipAi
```

## 手动启动方式

AI 服务：

```powershell
cd .\insight-spark-ai-service
python -m pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

后端服务：

```powershell
cd .\insight-spark-backend
mvn clean spring-boot:run
```

前端服务：

```powershell
cd .\insight-spark-frontend
npm install
npm run dev -- --host 0.0.0.0
```

## 默认账号

后端启动时会自动初始化演示账号：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 普通用户 | `demo-user` | `user123456` |
| 超级管理员 | `admin` | `admin123456` |

登录时需要先完成页面验证码。

## 常用验证命令

检查 AI 服务：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8000/health
```

检查后端服务：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/data/tables
```

前端构建：

```powershell
cd .\insight-spark-frontend
npm run build
```

后端测试：

```powershell
cd .\insight-spark-backend
mvn test
```

AI 服务语法检查：

```powershell
python -m py_compile .\insight-spark-ai-service\main.py
```

## 使用流程

1. 打开 `http://localhost:5173`。
2. 使用普通用户或管理员账号登录。
3. 进入“数据上传”，上传 `.xlsx`、`.xls` 或 `.csv` 文件。
4. 查看生成的数据表、字段语义、数据预览和质量信息。
5. 进入“对话查询”，选择数据源并输入自然语言问题。
6. 系统生成 SQL、完成审计和权限校验，返回图表或表格。
7. 可将结果沉淀到看板，或继续进行诊断报告、预测推演、批注协作等操作。

示例问题：

```text
按区域统计销售额
```

```text
按日期查看销售趋势
```

```text
各渠道销售额占比
```

```text
列出销售额最高的前 10 条记录
```

## 目录说明

```text
.
├── insight-spark-ai-service      # FastAPI AI 服务
├── insight-spark-backend         # Spring Boot 后端
├── insight-spark-frontend        # Vue 前端
├── sql                           # 数据库初始化与迁移脚本备份
├── test-data                     # 演示和验收测试数据
├── start-all.ps1                 # 本地一键启动脚本
├── stop-all.ps1                  # 本地停止脚本
├── 对话查询模块测试验收脚本.md
├── AI图表推荐扩展优化计划.md
└── Ollama本地部署qwen2.5-1.5b模型指南.md
```

后端启动时会自动创建多数组件所需表结构，并执行内置迁移脚本。`sql/` 目录主要用于手动排查、环境补齐或历史脚本留档。

## 常见问题

端口被占用：

```powershell
netstat -ano | findstr :8000
netstat -ano | findstr :8080
netstat -ano | findstr :5173
taskkill /PID 进程ID /F
```

AI 服务启动失败：

- 确认 Python 版本和依赖安装完成。
- 检查 `.env` 中的大模型配置是否正确。
- 未配置大模型时，可先用规则兜底能力验证主流程。

后端无法连接数据库：

- 确认 MySQL 已启动，且存在 `insight_spark` 数据库。
- 检查 `application.yml` 中的 `spring.datasource.url`、`username`、`password`。
- 如果暂时不用知识图谱能力，可在配置中关闭或调整 Neo4j 连接。

前端页面请求失败：

- 确认后端 `8080` 已启动。
- 浏览器开发者工具中检查接口响应和登录态。
- 重新执行 `npm install` 后再启动前端。
