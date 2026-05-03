# Insight Spark AI Service

Python AI 微服务，负责承接项目中的 Text-to-SQL、字段语义索引、图表推荐和智能诊断能力。

## 启动

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

## 当前接口

- `GET /health`
- `POST /ai/schema-index`
- `POST /ai/text-to-sql`
- `POST /ai/chart-recommend`
- `POST /ai/diagnose`

当前版本先完成可运行的 AI 服务边界和字段语义推理，后续可以把 `/ai/text-to-sql` 和 `/ai/diagnose` 内部替换为大模型、Neo4j、GraphRAG 或向量检索。
