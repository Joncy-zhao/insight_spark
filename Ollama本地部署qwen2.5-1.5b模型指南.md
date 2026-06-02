# Ollama 本地部署 qwen2.5:1.5b 模型指南

本文档用于团队成员在本地通过 Ollama 部署 `qwen2.5:1.5b`，并接入 Insight Spark 的 AI 服务，用于管理员端对话查询实验室测试本地私有化模型。

## 1. 安装 Ollama

访问 Ollama 官网下载安装：

```text
https://ollama.com/download
```

Windows 安装完成后，打开 PowerShell 验证：

```powershell
ollama --version
```

如果能看到版本号，说明安装成功。

## 2. 拉取 qwen2.5:1.5b 模型

在 PowerShell 中执行：

```powershell
ollama pull qwen2.5:1.5b
```

查看本地模型列表：

```powershell
ollama list
```

应能看到类似内容：

```text
NAME            ID              SIZE
qwen2.5:1.5b    xxxxxxxxxxxx    986 MB
```

## 3. 测试模型是否可用

执行：

```powershell
ollama run qwen2.5:1.5b
```

输入一句话测试：

```text
你好，请只回复 OK
```

如果模型正常回复，说明本地模型可用。

退出对话可按：

```text
Ctrl + D
```

或关闭当前终端窗口。

## 4. 确认 Ollama API 地址

Ollama 默认提供 OpenAI 兼容接口：

```text
http://localhost:11434/v1
```

可用 PowerShell 简单测试：

```powershell
$body = @{
  model = "qwen2.5:1.5b"
  messages = @(
    @{ role = "user"; content = "只回复 OK" }
  )
  temperature = 0.1
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri "http://localhost:11434/v1/chat/completions" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

如果返回中包含：

```text
model: qwen2.5:1.5b
choices
```

说明 Ollama API 可被项目调用。

## 5. 配置项目 AI 服务

进入项目 AI 服务目录：

```powershell
cd D:\siyouyun\homework\syy\insight-spark-ai-service
```

编辑 `.env` 文件。如果没有 `.env`，可复制 `.env.example` 后创建。

保留团队已有的商用模型配置，并追加本地模型配置：

```env
LOCAL_API_KEY=ollama
LOCAL_MODEL=qwen2.5:1.5b
LOCAL_BASE_URL=http://localhost:11434/v1
```

示例：

```env
OPENAI_API_KEY=你的商用模型Key
OPENAI_MODEL=qwen-plus
OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1

LOCAL_API_KEY=ollama
LOCAL_MODEL=qwen2.5:1.5b
LOCAL_BASE_URL=http://localhost:11434/v1
```

说明：

- `OPENAI_*`：默认商用模型配置。
- `LOCAL_*`：本地私有化模型配置。
- `LOCAL_API_KEY=ollama`：Ollama 本地接口不校验 API Key，填固定值即可。
- `LOCAL_MODEL` 必须与 `ollama list` 中的模型名完全一致。

## 6. 启动 Python AI 服务

首次启动前安装依赖：

```powershell
cd D:\siyouyun\homework\syy\insight-spark-ai-service
pip install -r requirements.txt
```

启动服务：

```powershell
uvicorn main:app --host 0.0.0.0 --port 8000
```

启动后访问：

```text
http://localhost:8000/health
```

如果配置成功，返回内容中的 `models` 应包含：

```text
qwen2.5:1.5b
```

也可以访问：

```text
http://localhost:8000/ai/models
```

查看当前 AI 服务识别到的模型列表。

## 7. 在管理员端切换模型

启动后端和前端后，进入：

```text
管理员端 -> 对话查询实验室
```

在“底层大模型”下拉框中应能看到：

```text
qwen-plus
qwen2.5:1.5b
```

选择 `qwen2.5:1.5b` 后点击“流式测试”，系统会通过：

```text
http://localhost:11434/v1/chat/completions
```

调用本地 Ollama 模型生成 SQL。

## 8. 常见问题

### 下拉框没有 qwen2.5:1.5b

检查：

```powershell
ollama list
```

确认模型名是否为：

```text
qwen2.5:1.5b
```

再检查 `insight-spark-ai-service/.env`：

```env
LOCAL_MODEL=qwen2.5:1.5b
LOCAL_BASE_URL=http://localhost:11434/v1
```

修改 `.env` 后必须重启 Python AI 服务。

### AI 服务仍然调用旧模型

`.env` 只在 Python AI 服务启动时读取。修改配置后，请停止并重新启动：

```powershell
uvicorn main:app --host 0.0.0.0 --port 8000
```

### Ollama 接口无法访问

检查 Ollama 是否正在运行：

```powershell
ollama list
```

如命令可正常返回，一般说明 Ollama 服务已启动。

也可直接测试接口：

```text
http://localhost:11434/v1/chat/completions
```

### qwen2.5:1.5b 生成 SQL 效果不稳定

`qwen2.5:1.5b` 是轻量模型，适合本地快速验证，但复杂业务黑话、复杂 SQL 和多表推理能力有限。复杂场景建议对比使用更大的本地模型或商用模型。

## 9. 推荐启动顺序

```text
1. 启动 Ollama
2. 确认 ollama list 能看到 qwen2.5:1.5b
3. 启动 insight-spark-ai-service
4. 启动 insight-spark-backend
5. 启动 insight-spark-frontend
6. 在管理员端对话查询实验室切换模型测试
```
