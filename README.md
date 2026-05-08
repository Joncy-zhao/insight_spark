# 析数灵犀：AI驱动的对话式智能BI系统运行说明

本项目分为三个主要部分：

- `insight-spark-backend`：Spring Boot 后端，负责上传、建表、查询、安全校验、调用 AI 服务。
- `insight-spark-ai-service`：Python FastAPI AI 服务，负责 Text-to-SQL、图表推荐、诊断报告等 AI 能力。
- `insight-spark-frontend`：Vue3 + Element Plus 前端页面。

## 启动顺序

建议按下面顺序启动：

1. 启动 MySQL
2. 启动 Python AI 服务
3. 启动 Spring Boot 后端
4. 启动 Vue 前端
5. 浏览器访问前端页面

## 1. 启动 MySQL

先确认本机 MySQL 已启动，并且存在数据库：

```sql
CREATE DATABASE IF NOT EXISTS insight_spark DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

后端数据库配置在：

```text
insight-spark-backend/src/main/resources/application.yml
```

当前连接配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/insight_spark?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: ${MYSQL_PASSWORD:root}
```

## 2. 启动 Python AI 服务

打开第一个终端：

```powershell
cd D:\Javatest\insight-spark\insight-spark-ai-service
```

首次运行需要安装依赖：

```powershell
pip install -r requirements.txt
```

启动 AI 服务：

```powershell
uvicorn main:app --host 0.0.0.0 --port 8000
```

检查是否启动成功：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8000/health
```

正常返回：

```json
{"status":"ok"}
```

说明：如果 Python AI 服务没有启动，后端会自动使用 Java 内置兜底逻辑，但期末演示时建议启动它。

## 3. 启动 Spring Boot 后端

打开第二个终端：

```powershell
cd D:\Javatest\insight-spark\insight-spark-backend
```

启动后端：

```powershell
mvn spring-boot:run
mvn clean spring-boot:run
```

后端默认端口：

```text
http://localhost:8080
```

检查后端是否启动成功：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/data/tables
```

正常返回格式：

```json
{"code":200,"message":"success","data":[]}
```

## 4. 启动 Vue 前端

打开第三个终端：

```powershell
cd D:\Javatest\insight-spark\insight-spark-frontend
```

首次运行如果没有依赖，先安装：

```powershell
npm install
```

启动前端：

```powershell
npm run dev -- --host 0.0.0.0
```

前端访问地址：

```text
http://localhost:5173
```

## 5. 使用流程

1. 打开 `http://localhost:5173`
2. 进入“数据上传”
3. 上传 `.xlsx`、`.xls` 或 `.csv`
4. 上传成功后选择生成的数据表
5. 查看“数据预览”和“字段语义”
6. 进入“对话查询”
7. 输入问题，例如：

```text
按省份统计销售额
```

```text
按日期看销售趋势
```

```text
分类占比
```

系统流程：

```text
前端提问 -> 后端读取表结构 -> 调用 Python AI 服务生成 SQL
-> 后端执行查询 -> 返回图表数据 -> 前端 ECharts 渲染
```

## 常用验证命令

后端测试：

```powershell
cd D:\Javatest\insight-spark\insight-spark-backend
mvn -q testss
```

前端打包：

```powershell
cd D:\Javatest\insight-spark\insight-spark-frontend
npm run build
```

Python 语法检查：

```powershell
cd D:\Javatest\insight-spark
python -m py_compile insight-spark-ai-service\main.py
```

## 端口说明

| 模块 | 端口 | 地址 |
| --- | --- | --- |
| Python AI 服务 | 8000 | `http://localhost:8000` |
| Spring Boot 后端 | 8080 | `http://localhost:8080` |
| Vue 前端 | 5173 | `http://localhost:5173` |

## 如果端口被占用

查看端口占用：

```powershell
netstat -ano | findstr :8080
netstat -ano | findstr :5173
netstat -ano | findstr :8000
```

结束指定进程：

```powershell
taskkill /PID 进程ID /F
```

## 推荐演示顺序

1. 展示项目目录结构
2. 启动 Python AI 服务
3. 启动 Spring Boot 后端
4. 启动 Vue 前端
5. 上传 Excel/CSV
6. 展示生成的数据表、字段语义、数据预览
7. 进入对话查询
8. 展示 AI 生成 SQL、字段匹配、图表结果
9. 说明后续模块：SQL 安全审计、权限中心、官方数据源、智能诊断报告
