from collections import defaultdict
from math import sqrt
from typing import Any
import json
import os
from urllib import error, request

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


app = FastAPI(title="Insight Spark AI Service", version="0.1.0")


def load_local_env_file(path: str = ".env") -> None:
    if not os.path.exists(path):
        return
    try:
        with open(path, "r", encoding="utf-8") as env_file:
            for raw_line in env_file:
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                key = key.strip()
                value = value.strip().strip('"').strip("'")
                os.environ.setdefault(key, value)
    except OSError:
        pass


load_local_env_file()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "qwen-plus").strip()
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")


class FieldMeta(BaseModel):
    sourceFieldName: str | None = None
    columnName: str
    fieldType: str = "TEXT"
    displayName: str
    fieldComment: str | None = None
    sensitive: bool | int | None = False
    sortOrder: int | None = None


class TextToSqlRequest(BaseModel):
    question: str
    tableName: str
    fields: list[FieldMeta]
    previewRows: list[dict[str, Any]] = []


class ChartRecommendRequest(BaseModel):
    columns: list[str] = []
    rows: list[dict[str, Any]] = []


class DiagnoseRequest(BaseModel):
    tableName: str
    metricField: str
    dimensionFields: list[str] = []
    timeField: str | None = None
    rows: list[dict[str, Any]] = []
    detailLevel: str = "detailed"


class GraphRagDiagnoseRequest(BaseModel):
    question: str
    tableName: str
    metricField: str
    dimensionFields: list[str] = []
    timeField: str | None = None
    rows: list[dict[str, Any]] = []
    queryRows: list[dict[str, Any]] = []
    graphPath: dict[str, Any] = {}
    graphContext: list[dict[str, Any]] = []
    docEvidence: list[dict[str, Any]] = []
    docChunks: list[dict[str, Any]] = []
    detailLevel: str = "detailed"
    anomalyType: str = "fluctuation"


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ai/schema-index")
def schema_index(payload: TextToSqlRequest) -> dict[str, Any]:
    return {
        "tableName": payload.tableName,
        "fieldCount": len(payload.fields),
        "indexedTerms": [
            {
                "term": field.displayName,
                "columnName": field.columnName,
                "fieldType": field.fieldType,
            }
            for field in payload.fields
        ],
    }


@app.post("/ai/text-to-sql")
def text_to_sql(payload: TextToSqlRequest) -> dict[str, Any]:
    if not payload.fields:
        raise HTTPException(status_code=400, detail="当前数据表没有字段元信息，请先重新上传文件或选择有效数据表。")

    if OPENAI_API_KEY:
        ai_result = call_openai_text_to_sql(payload)
        if ai_result:
            return ai_result

    dimension = choose_dimension(payload.question, payload.fields)
    metric = choose_metric(payload.question, payload.fields)
    chart_type = choose_chart_type(payload.question, dimension)

    if metric:
        value_expr = f"SUM(CAST(NULLIF(`{metric.columnName}`, '') AS DECIMAL(18,2)))"
        metric_name = metric.displayName
        metric_key = metric.columnName
    else:
        value_expr = "COUNT(1)"
        metric_name = "记录数"
        metric_key = "value"

    dimension_expr = build_dimension_expression(payload.question, dimension)
    order_expr = "dim_name ASC" if chart_type == "line" else "metric_value DESC"
    sql = (
        f"SELECT {dimension_expr} AS dim_name, {value_expr} AS metric_value "
        f"FROM `{payload.tableName}` "
        f"WHERE {build_dimension_filter(payload.question, dimension)} "
        f"GROUP BY {dimension_expr} "
        f"ORDER BY {order_expr} LIMIT 30"
    )

    return {
        "sql": sql,
        "chartType": chart_type,
        "fieldMapping": {
            "dimension": dimension.displayName,
            "metric": metric_name,
            "dimensionKey": dimension.columnName,
            "metricKey": metric_key,
            "dimensionExpr": dimension_expr,
        },
        "reasoning": [
            f"识别维度字段：{dimension.displayName}",
            f"识别指标字段：{metric_name}",
            f"推荐图表类型：{chart_type}",
        ],
        "model": "rule-based-fallback",
    }


def build_dimension_expression(question: str, dimension: FieldMeta) -> str:
    column = f"`{dimension.columnName}`"
    if dimension.fieldType == "DATE":
        return date_expression(column, question)
    if any(token in question for token in ["按月", "月份", "月度"]):
        return f"DATE_FORMAT({column}, '%Y-%m')"
    if any(token in question for token in ["按年", "年度"]):
        return f"DATE_FORMAT({column}, '%Y')"
    if any(token in question for token in ["按周", "周"]):
        return f"DATE_FORMAT({column}, '%x-%v')"
    if any(token in question for token in ["按天", "每日", "天"]):
        return f"DATE_FORMAT({column}, '%Y-%m-%d')"
    return column


def build_dimension_filter(question: str, dimension: FieldMeta) -> str:
    column = f"`{dimension.columnName}`"
    if dimension.fieldType == "DATE":
        if any(token in question for token in ["按月", "月份", "月度"]):
            return f"{column} IS NOT NULL"
        return f"{column} IS NOT NULL"
    return f"{column} IS NOT NULL AND {column} <> ''"


def date_expression(column: str, question: str) -> str:
    if any(token in question for token in ["按月", "月份", "月度"]):
        return f"DATE_FORMAT({column}, '%Y-%m')"
    if any(token in question for token in ["按年", "年度"]):
        return f"DATE_FORMAT({column}, '%Y')"
    if any(token in question for token in ["按周", "周"]):
        return f"DATE_FORMAT({column}, '%x-%v')"
    if any(token in question for token in ["按天", "每日", "天"]):
        return f"DATE_FORMAT({column}, '%Y-%m-%d')"
    return column


@app.post("/ai/chart-recommend")
def chart_recommend(payload: ChartRecommendRequest) -> dict[str, str]:
    if len(payload.columns) >= 2 and any("date" in col.lower() or "time" in col.lower() for col in payload.columns):
        return {"chartType": "line"}
    if len(payload.rows) <= 8:
        return {"chartType": "pie"}
    return {"chartType": "bar"}


@app.post("/ai/diagnose")
def diagnose(payload: DiagnoseRequest) -> dict[str, Any]:
    parsed_rows = []
    values = []
    for index, row in enumerate(payload.rows):
        value = to_float(row.get(payload.metricField))
        if value is None:
            continue
        parsed_row = dict(row)
        parsed_row["_rowIndex"] = index + 1
        parsed_row["_metricValue"] = value
        parsed_rows.append(parsed_row)
        values.append(value)

    if not values:
        return {
            "title": "智能诊断报告",
            "summary": "当前数据不足，无法完成异常诊断。",
            "statistics": {},
            "anomalies": [],
            "dimensionContributions": [],
            "trendInsights": [],
            "suggestions": ["请确认指标字段是否为数值类型。"],
            "reportMarkdown": "## 智能诊断报告\n\n当前数据不足，无法完成异常诊断。"
        }

    avg = sum(values) / len(values)
    max_value = max(values)
    min_value = min(values)
    variance = sum((value - avg) ** 2 for value in values) / len(values)
    std = sqrt(variance)
    total = sum(values)

    anomalies = detect_zscore_anomalies(parsed_rows, avg, std, payload.metricField)
    dimension_contributions = analyze_dimension_contributions(parsed_rows, payload.dimensionFields, total)
    trend_insights = analyze_trend(parsed_rows, payload.timeField)
    root_causes = infer_root_causes(anomalies, dimension_contributions, trend_insights, payload.metricField)
    factor_chart_blocks = build_factor_chart_blocks(dimension_contributions, trend_insights)
    suggestions = build_diagnosis_suggestions(anomalies, dimension_contributions, trend_insights)
    summary = (
        f"共分析 {len(values)} 条有效记录，{payload.metricField} 合计 {total:.2f}，"
        f"均值 {avg:.2f}，最大值 {max_value:.2f}，最小值 {min_value:.2f}。"
        f"系统识别出 {len(anomalies)} 个明显异常点。"
    )
    reasoning_logs = build_base_reasoning_logs(
        len(values), anomalies, dimension_contributions, trend_insights, payload.metricField
    )
    report_markdown = build_report_markdown(
        payload,
        summary,
        {
            "count": len(values),
            "total": round(total, 2),
            "avg": round(avg, 2),
            "max": round(max_value, 2),
            "min": round(min_value, 2),
            "std": round(std, 2),
        },
        anomalies,
        dimension_contributions,
        trend_insights,
        root_causes,
        factor_chart_blocks,
        suggestions,
        reasoning_logs,
    )

    return {
        "title": f"{payload.tableName} 智能诊断报告",
        "summary": summary,
        "statistics": {
            "count": len(values),
            "total": round(total, 2),
            "avg": round(avg, 2),
            "max": round(max_value, 2),
            "min": round(min_value, 2),
            "std": round(std, 2),
        },
        "anomalies": anomalies,
        "dimensionContributions": dimension_contributions,
        "trendInsights": trend_insights,
        "rootCauses": root_causes,
        "factorChartBlocks": factor_chart_blocks,
        "suggestions": suggestions,
        "reasoningLogs": reasoning_logs,
        "reportMarkdown": report_markdown,
    }


@app.post("/ai/graphrag/diagnose")
def graphrag_diagnose(payload: GraphRagDiagnoseRequest) -> dict[str, Any]:
    query_rows = payload.queryRows or payload.rows
    base = diagnose(DiagnoseRequest(
        tableName=payload.tableName,
        metricField=payload.metricField,
        dimensionFields=payload.dimensionFields,
        timeField=payload.timeField,
        rows=query_rows,
        detailLevel=payload.detailLevel,
    ))
    graph_nodes = payload.graphPath.get("nodes") or payload.graphContext
    graph_edges = payload.graphPath.get("edges") or []
    path_text = payload.graphPath.get("pathText") or ""
    chunks = payload.docEvidence or payload.docChunks
    doc_evidence = [
        {
            "source": chunk.get("source") or f"文档 {chunk.get('docId', '')} 第 {chunk.get('chunkIndex', '')} 段",
            "text": str(chunk.get("chunkText", ""))[:220],
            "score": chunk.get("score"),
            "matchedKeywords": chunk.get("matchedKeywords", []),
        }
        for chunk in chunks[:5]
    ]
    graph_path = [
        {"nodeType": item.get("nodeType"), "label": item.get("label"), "sourceId": item.get("sourceId")}
        for item in graph_nodes[:8]
    ]
    base["rootCauses"] = enrich_root_causes(base.get("rootCauses", []), doc_evidence, graph_nodes, graph_edges, payload.metricField)
    base["summary"] = f"{base.get('summary', '')} 已结合 {len(doc_evidence)} 条文档证据和 {len(graph_nodes)} 个图谱节点进行 GraphRAG 根因推理。"
    base["evidence"] = doc_evidence
    base["docEvidence"] = doc_evidence
    base["reasoningPath"] = graph_path
    base["graphPath"] = {"nodes": graph_nodes[:16], "edges": graph_edges[:32], "pathText": path_text}
    base["graphReasoningPath"] = path_text or " -> ".join(str(item.get("label") or item.get("sourceId") or item.get("nodeType")) for item in graph_path)
    base["confidence"] = round(max((cause.get("confidence", 0) for cause in base["rootCauses"]), default=0), 2)
    base["reasoningLogs"] = build_graphrag_reasoning_logs(
        base.get("reasoningLogs", []), graph_nodes, graph_edges, doc_evidence, base["rootCauses"], payload.anomalyType
    )
    evidence_lines = [f"- {item['source']}：{item['text']}" for item in doc_evidence]
    if base["graphReasoningPath"]:
        evidence_lines.append("- 图谱推理路径：" + base["graphReasoningPath"])
    if payload.detailLevel == "simple":
        base["reportMarkdown"] = build_simple_graphrag_report(base, payload, doc_evidence)
    else:
        base["reportMarkdown"] = (
            f"{base.get('reportMarkdown', '')}\n\n"
            "## GraphRAG 根因推理\n\n"
            + build_graphrag_markdown(base["rootCauses"], base["graphReasoningPath"], doc_evidence, base["reasoningLogs"])
            + "\n\n## 关联证据\n\n"
            + ("\n".join(evidence_lines) if evidence_lines else "- 暂未检索到外部证据，建议先上传知识文档并同步知识图谱。")
        )
    return base


@app.post("/ai/graphrag/diagnose-legacy")
def graphrag_diagnose_legacy(payload: GraphRagDiagnoseRequest) -> dict[str, Any]:
    base = diagnose(DiagnoseRequest(
        tableName=payload.tableName,
        metricField=payload.metricField,
        rows=payload.rows,
    ))
    doc_evidence = []
    for chunk in payload.docChunks[:5]:
        doc_evidence.append({
            "source": chunk.get("source") or f"文档 {chunk.get('docId', '')} 第 {chunk.get('chunkIndex', '')} 段",
            "text": str(chunk.get("chunkText", ""))[:220],
        })
    graph_path = [
        {
            "nodeType": item.get("nodeType"),
            "label": item.get("label"),
            "sourceId": item.get("sourceId"),
        }
        for item in payload.graphContext[:8]
    ]
    evidence_lines = [f"- {item['source']}：{item['text']}" for item in doc_evidence]
    if graph_path:
        evidence_lines.append("- 知识图谱路径：" + " -> ".join(str(item.get("label") or item.get("sourceId") or item.get("nodeType")) for item in graph_path))

    base["summary"] = f"{base.get('summary', '')} 已结合 {len(doc_evidence)} 条文档证据和 {len(graph_path)} 个图谱节点进行 GraphRAG 推理。"
    base["evidence"] = doc_evidence
    base["reasoningPath"] = graph_path
    base["reportMarkdown"] = (
        f"{base.get('reportMarkdown', '')}\n\n"
        "## 关联证据\n\n"
        + ("\n".join(evidence_lines) if evidence_lines else "- 暂未检索到外部证据，建议先上传知识文档并同步知识图谱。")
    )
    return base


def enrich_root_causes(
    base_causes: list[dict[str, Any]],
    doc_evidence: list[dict[str, Any]],
    graph_nodes: list[dict[str, Any]],
    graph_edges: list[dict[str, Any]],
    metric_field: str,
) -> list[dict[str, Any]]:
    enriched = list(base_causes or [])
    graph_confidence = min(0.92, 0.45 + len(graph_nodes) * 0.03 + len(graph_edges) * 0.01)
    doc_confidence = min(0.9, 0.5 + len(doc_evidence) * 0.07)
    if graph_nodes:
        labels = " -> ".join(str(node.get("label") or node.get("sourceId") or node.get("nodeType")) for node in graph_nodes[:5])
        enriched.append({
            "level": "MEDIUM" if graph_confidence < 0.75 else "HIGH",
            "causeType": "图谱路径关联根因",
            "impactField": metric_field,
            "evidence": f"图谱路径显示指标与相关表、字段、标签或历史报告存在关联：{labels}",
            "confidence": round(graph_confidence, 2),
        })
    if doc_evidence:
        top_doc = doc_evidence[0]
        enriched.append({
            "level": "MEDIUM" if doc_confidence < 0.75 else "HIGH",
            "causeType": "文档证据支持根因",
            "impactField": metric_field,
            "evidence": f"{top_doc.get('source')} 提到：{top_doc.get('text')}",
            "confidence": round(doc_confidence, 2),
        })
    if not enriched:
        enriched.append({
            "level": "LOW",
            "causeType": "证据不足",
            "impactField": metric_field,
            "evidence": "当前未检索到图谱路径或文档片段，根因仅基于指标分布推断。",
            "confidence": 0.42,
        })
    return sorted(enriched, key=lambda item: item.get("confidence", 0), reverse=True)[:8]


def build_base_reasoning_logs(
    value_count: int,
    anomalies: list[dict[str, Any]],
    contributions: list[dict[str, Any]],
    trend_insights: list[dict[str, Any]],
    metric_field: str,
) -> list[dict[str, Any]]:
    return [
        {
            "step": 1,
            "title": "扫描原始异常数据",
            "status": "completed",
            "detail": f"读取 {value_count} 条有效指标记录，围绕 {metric_field} 计算合计、均值、标准差和 Z-Score。",
        },
        {
            "step": 2,
            "title": "识别异常节点",
            "status": "completed",
            "detail": f"识别出 {len(anomalies)} 个明显异常点，并保留最大/最小/波动幅度等关键数值。",
        },
        {
            "step": 3,
            "title": "拆解维度与趋势",
            "status": "completed",
            "detail": f"完成 {len(contributions)} 个维度贡献拆解和 {len(trend_insights)} 条趋势判断。",
        },
    ]


def build_graphrag_reasoning_logs(
    base_logs: list[dict[str, Any]],
    graph_nodes: list[dict[str, Any]],
    graph_edges: list[dict[str, Any]],
    doc_evidence: list[dict[str, Any]],
    root_causes: list[dict[str, Any]],
    anomaly_type: str,
) -> list[dict[str, Any]]:
    logs = list(base_logs or [])
    logs.extend([
        {
            "step": 4,
            "title": "命中 Neo4j 表/字段/历史报告节点",
            "status": "completed",
            "detail": f"围绕异常类型 {anomaly_type} 命中 {len(graph_nodes)} 个图谱节点、{len(graph_edges)} 条关联边。",
        },
        {
            "step": 5,
            "title": "扩展企业内部文档与行业研报证据",
            "status": "completed",
            "detail": f"检索到 {len(doc_evidence)} 条文档/研报证据片段，用于佐证异常因素。",
        },
        {
            "step": 6,
            "title": "输出根因定位与改进建议",
            "status": "completed",
            "detail": f"生成 {len(root_causes)} 条根因假设，按置信度排序输出决策建议。",
        },
    ])
    return logs


def build_simple_graphrag_report(base: dict[str, Any], payload: GraphRagDiagnoseRequest, doc_evidence: list[dict[str, Any]]) -> str:
    root_causes = base.get("rootCauses", [])[:3]
    suggestions = base.get("suggestions", [])[:3]
    lines = [
        f"# {payload.tableName} 智能诊断报告（简易版）",
        "",
        "## 诊断摘要",
        base.get("summary", ""),
        "",
        "## 根因结论",
    ]
    if root_causes:
        lines.extend([f"- {item.get('causeType')}：{item.get('evidence')}，置信度 {item.get('confidence')}" for item in root_causes])
    else:
        lines.append("- 暂未形成高置信度根因。")
    lines.extend(["", "## 改进建议"])
    lines.extend([f"- {item}" for item in suggestions] or ["- 建议补充维度字段和企业文档后重新生成详细报告。"])
    lines.extend(["", "## GraphRAG 摘要"])
    lines.append(f"- 图谱路径：{base.get('graphReasoningPath') or '暂无'}")
    lines.append(f"- 文档证据：{len(doc_evidence)} 条")
    return "\n".join(lines)


def build_graphrag_markdown(
    root_causes: list[dict[str, Any]],
    graph_path_text: str,
    doc_evidence: list[dict[str, Any]],
    reasoning_logs: list[dict[str, Any]],
) -> str:
    lines = []
    lines.append("### 可能根因")
    for cause in root_causes[:5]:
        lines.append(
            f"- [{cause.get('level', 'MEDIUM')}] {cause.get('causeType')}，置信度 {cause.get('confidence', 0):.2f}：{cause.get('evidence')}"
        )
    lines.append("")
    lines.append("### 图谱推理路径")
    lines.append(graph_path_text or "暂无图谱路径。")
    lines.append("")
    lines.append("### 文档证据来源")
    if doc_evidence:
        for item in doc_evidence:
            lines.append(f"- {item.get('source')}，评分 {item.get('score', '-')}: {item.get('text')}")
    else:
        lines.append("- 暂无文档证据。")
    lines.append("")
    lines.append("### 推理过程日志")
    for log in reasoning_logs:
        lines.append(f"- Step {log.get('step')} {log.get('title')}：{log.get('detail')}")
    return "\n".join(lines)


def to_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        if isinstance(value, str):
            value = value.replace(",", "").strip()
            if not value:
                return None
        return float(value)
    except (TypeError, ValueError):
        return None


def detect_zscore_anomalies(rows: list[dict[str, Any]], avg: float, std: float, metric_field: str) -> list[dict[str, Any]]:
    if std == 0:
        return []
    anomalies = []
    for row in rows:
        value = row["_metricValue"]
        z_score = (value - avg) / std
        if abs(z_score) >= 2:
            anomalies.append({
                "type": "z_score",
                "level": "HIGH" if abs(z_score) >= 3 else "MEDIUM",
                "rowIndex": row["_rowIndex"],
                "metricField": metric_field,
                "metricValue": round(value, 2),
                "zScore": round(z_score, 2),
                "direction": "高于均值" if z_score > 0 else "低于均值",
                "description": f"第 {row['_rowIndex']} 行 {metric_field}={value:.2f}，{('高于' if z_score > 0 else '低于')}均值 {abs(z_score):.2f} 个标准差。",
            })
    return sorted(anomalies, key=lambda item: abs(item["zScore"]), reverse=True)[:10]


def analyze_dimension_contributions(
    rows: list[dict[str, Any]],
    dimension_fields: list[str],
    total: float,
) -> list[dict[str, Any]]:
    contributions = []
    if total == 0:
        return contributions

    for dimension_field in dimension_fields[:3]:
        bucket = defaultdict(float)
        for row in rows:
            dimension_value = row.get(dimension_field)
            if dimension_value is None or str(dimension_value).strip() == "":
                dimension_value = "未填写"
            bucket[str(dimension_value)] += row["_metricValue"]

        top_items = sorted(bucket.items(), key=lambda item: item[1], reverse=True)[:5]
        contributions.append({
            "dimensionField": dimension_field,
            "topItems": [
                {
                    "name": name,
                    "value": round(value, 2),
                    "share": round(value / total * 100, 2),
                }
                for name, value in top_items
            ],
        })
    return contributions


def analyze_trend(rows: list[dict[str, Any]], time_field: str | None) -> list[dict[str, Any]]:
    if not time_field:
        return []

    bucket = defaultdict(float)
    for row in rows:
        time_value = row.get(time_field)
        if time_value is None or str(time_value).strip() == "":
            continue
        bucket[str(time_value)] += row["_metricValue"]

    ordered = sorted(bucket.items(), key=lambda item: item[0])
    if len(ordered) < 2:
        return []

    first_name, first_value = ordered[0]
    last_name, last_value = ordered[-1]
    change = last_value - first_value
    change_rate = (change / first_value * 100) if first_value else 0
    direction = "上升" if change > 0 else "下降" if change < 0 else "持平"

    return [{
        "timeField": time_field,
        "start": first_name,
        "end": last_name,
        "startValue": round(first_value, 2),
        "endValue": round(last_value, 2),
        "change": round(change, 2),
        "changeRate": round(change_rate, 2),
        "description": f"{time_field} 从 {first_name} 到 {last_name} 整体{direction}，变化 {change:.2f}，变化率 {change_rate:.2f}%。",
    }]


def infer_root_causes(
    anomalies: list[dict[str, Any]],
    contributions: list[dict[str, Any]],
    trend_insights: list[dict[str, Any]],
    metric_field: str,
) -> list[dict[str, Any]]:
    causes = []
    if anomalies:
        top = anomalies[0]
        causes.append({
            "level": "HIGH",
            "causeType": "异常点拉动",
            "evidence": top["description"],
            "impactField": metric_field,
            "confidence": 0.86 if abs(top["zScore"]) >= 3 else 0.72,
        })
    if contributions and contributions[0]["topItems"]:
        top_dimension = contributions[0]
        top_item = top_dimension["topItems"][0]
        causes.append({
            "level": "MEDIUM",
            "causeType": "头部维度贡献集中",
            "evidence": f"{top_dimension['dimensionField']}={top_item['name']} 贡献占比 {top_item['share']}%",
            "impactField": top_dimension["dimensionField"],
            "confidence": min(0.9, 0.5 + top_item["share"] / 100),
        })
    if trend_insights:
        trend = trend_insights[0]
        causes.append({
            "level": "MEDIUM",
            "causeType": "时间趋势变化",
            "evidence": trend["description"],
            "impactField": trend["timeField"],
            "confidence": 0.68,
        })
    if not causes:
        causes.append({
            "level": "LOW",
            "causeType": "未发现显著单点根因",
            "evidence": "当前样本未触发异常点、集中贡献或趋势变化规则。",
            "impactField": metric_field,
            "confidence": 0.45,
        })
    return causes


def build_factor_chart_blocks(
    contributions: list[dict[str, Any]],
    trend_insights: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    blocks = []
    for contribution in contributions:
        blocks.append({
            "title": f"{contribution['dimensionField']} 贡献拆解",
            "chartType": "bar",
            "xField": "name",
            "yField": "value",
            "data": contribution["topItems"],
        })
    for trend in trend_insights:
        blocks.append({
            "title": f"{trend['timeField']} 趋势变化",
            "chartType": "line",
            "xField": "time",
            "yField": "value",
            "data": [
                {"time": trend["start"], "value": trend["startValue"]},
                {"time": trend["end"], "value": trend["endValue"]},
            ],
        })
    return blocks[:6]


def build_diagnosis_suggestions(
    anomalies: list[dict[str, Any]],
    contributions: list[dict[str, Any]],
    trend_insights: list[dict[str, Any]],
) -> list[str]:
    suggestions = []
    if anomalies:
        suggestions.append("优先核查 Z-Score 绝对值最高的异常记录，确认是否为录入错误、活动冲击或业务突变。")
    if contributions:
        top_dimension = contributions[0]
        if top_dimension["topItems"]:
            top_item = top_dimension["topItems"][0]
            suggestions.append(
                f"重点关注维度 {top_dimension['dimensionField']} 中的 {top_item['name']}，其贡献占比约 {top_item['share']}%。"
            )
    if trend_insights:
        suggestions.append("结合时间趋势判断异常是否持续存在，必要时按时间窗口进一步拆解。")
    if not suggestions:
        suggestions.append("当前未发现显著异常，可继续增加维度字段或时间字段进行更细粒度诊断。")
    return suggestions


def build_report_markdown(
    payload: DiagnoseRequest,
    summary: str,
    statistics: dict[str, Any],
    anomalies: list[dict[str, Any]],
    contributions: list[dict[str, Any]],
    trend_insights: list[dict[str, Any]],
    root_causes: list[dict[str, Any]],
    factor_chart_blocks: list[dict[str, Any]],
    suggestions: list[str],
    reasoning_logs: list[dict[str, Any]],
) -> str:
    if payload.detailLevel == "simple":
        lines = [
            f"# {payload.tableName} 智能诊断报告（简易版）",
            "",
            "## 诊断摘要",
            summary,
            "",
            "## 核心数值",
            f"- 有效记录数：{statistics['count']}",
            f"- 指标合计：{statistics['total']}，平均值：{statistics['avg']}，标准差：{statistics['std']}",
            "",
            "## 根因结论",
        ]
        lines.extend([f"- [{cause['level']}] {cause['causeType']}：{cause['evidence']}" for cause in root_causes[:3]])
        lines.extend(["", "## 改进建议"])
        lines.extend([f"- {suggestion}" for suggestion in suggestions[:3]])
        return "\n".join(lines)

    lines = [
        f"# {payload.tableName} 智能诊断报告",
        "",
        "## 诊断摘要",
        summary,
        "",
        "## 核心统计",
        f"- 有效记录数：{statistics['count']}",
        f"- 指标合计：{statistics['total']}",
        f"- 平均值：{statistics['avg']}",
        f"- 最大值：{statistics['max']}",
        f"- 最小值：{statistics['min']}",
        f"- 标准差：{statistics['std']}",
        "",
        "## 异常识别",
    ]
    if anomalies:
        lines.extend([f"- {item['description']}" for item in anomalies[:5]])
    else:
        lines.append("- 未发现 Z-Score 绝对值超过 2 的明显异常点。")

    lines.extend(["", "## 维度贡献"])
    if contributions:
        for contribution in contributions:
            lines.append(f"- {contribution['dimensionField']}")
            for item in contribution["topItems"]:
                lines.append(f"  - {item['name']}：{item['value']}，占比 {item['share']}%")
    else:
        lines.append("- 未选择维度字段，无法进行维度贡献拆解。")

    lines.extend(["", "## 趋势判断"])
    if trend_insights:
        lines.extend([f"- {item['description']}" for item in trend_insights])
    else:
        lines.append("- 未选择时间字段或时间数据不足，暂不生成趋势判断。")

    lines.extend(["", "## GraphRAG 根因假设"])
    for cause in root_causes:
        lines.append(f"- [{cause['level']}] {cause['causeType']}：{cause['evidence']}，置信度 {cause['confidence']:.2f}")

    lines.extend(["", "## 关联因素图表块"])
    if factor_chart_blocks:
        for block in factor_chart_blocks:
            lines.append(f"- {block['title']}（{block['chartType']}）：{len(block['data'])} 个数据点")
    else:
        lines.append("- 暂无可生成的关联因素图表块。")

    lines.extend(["", "## 建议动作"])
    lines.extend([f"- {suggestion}" for suggestion in suggestions])
    lines.extend(["", "## 推理过程日志"])
    lines.extend([f"- Step {log['step']} {log['title']}：{log['detail']}" for log in reasoning_logs])
    return "\n".join(lines)


def call_openai_text_to_sql(payload: TextToSqlRequest) -> dict[str, Any] | None:
    prompt = build_text_to_sql_prompt(payload)
    body = json.dumps({
        "model": OPENAI_MODEL,
        "messages": [
            {"role": "system", "content": (
                "你是企业级 Text-to-SQL 专家。\n"
                "目标：根据用户问题、字段元信息和数据类型生成安全、可执行、跨表结构适应性强的 SQL。\n"
                "必须遵守：\n"
                "1. 只输出严格 JSON，不要输出解释性文本。\n"
                "2. SQL 必须只读，禁止 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE。\n"
                "3. 所有列名必须使用反引号包裹。\n"
                "4. 禁止使用容易冲突的别名，如 date、time、count、value 直接作为最终别名。\n"
                "5. 如果维度字段是日期/时间字符串，优先输出可兼容 MySQL 的日期桶表达式，且别名使用 dim_name。\n"
                "6. 如果无法确定高置信度字段，应优先选择字段类型与语义最接近的列，并在 reasoning 中说明。"
            )},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.1,
    }).encode("utf-8")

    req = request.Request(
        f"{OPENAI_BASE_URL}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=25) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = payload_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if not isinstance(parsed, dict) or not parsed.get("sql"):
            return None
        parsed = normalize_ai_sql_result(parsed, payload)
        parsed.setdefault("model", OPENAI_MODEL)
        parsed.setdefault("reasoning", ["由大模型生成"])
        return parsed
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def build_text_to_sql_prompt(payload: TextToSqlRequest) -> str:
    fields_text = "\n".join(
        f"- columnName={field.columnName}, displayName={field.displayName}, fieldType={field.fieldType}, fieldComment={field.fieldComment or ''}"
        for field in payload.fields
    )
    preview_text = "\n".join(
        f"- {json.dumps(row, ensure_ascii=False)}" for row in payload.previewRows[:5]
    ) or "暂无预览样本"
    examples = get_prompt_examples(payload.fields)
    return (
        f"用户问题：{payload.question}\n"
        f"目标表：{payload.tableName}\n"
        f"字段信息：\n{fields_text}\n\n"
        f"预览样本：\n{preview_text}\n\n"
        f"历史高质量示例：\n{examples}\n\n"
        "请输出严格 JSON，格式如下：\n"
        "{\n"
        '  "sql": "SELECT ...",\n'
        '  "chartType": "bar|line|pie",\n'
        '  "fieldMapping": {"dimension": "", "metric": "", "dimensionKey": "", "metricKey": ""},\n'
        '  "reasoning": ["", ""],\n'
        '  "confidence": 0.0\n'
        "}\n"
        "要求：只输出 JSON，不要输出多余文本；SQL 必须只读；优先使用用户语义最匹配的维度和指标；"
        "如果用户问题里出现‘按省份/地区/城市/分类/品类/产品名/订单日期/月份’等模式，请尽量选择语义对应字段。"
        "如果预览样本中字段值明显像地区、省份、日期或金额，请优先结合样本值判断，而不是只看列名。"
        "如果是时间维度字符串，优先使用 DATE_FORMAT / STR_TO_DATE / CAST 等兼容 MySQL 的方式，并避免使用 DATE(...) 直接包裹非日期列。"
    )


def get_prompt_examples(fields: list[FieldMeta]) -> str:
    examples = []
    if fields:
        first_text = first_by_type(fields, "TEXT") or fields[0]
        first_number = first_by_type(fields, "NUMBER")
        first_date = first_by_type(fields, "DATE")
        if first_text:
            examples.append(
                f"1) 问题：按{first_text.displayName}分组统计\n"
                f"   SQL：SELECT `{first_text.columnName}` AS dim_name, COUNT(1) AS metric_value FROM `table` GROUP BY `{first_text.columnName}` ORDER BY metric_value DESC LIMIT 30"
            )
        if first_number:
            examples.append(
                f"2) 问题：统计{first_number.displayName}总和\n"
                f"   SQL：SELECT `{first_text.columnName if first_text else first_number.columnName}` AS dim_name, SUM(`{first_number.columnName}`) AS metric_value FROM `table` GROUP BY `{first_text.columnName if first_text else first_number.columnName}` LIMIT 30"
            )
        if first_date:
            examples.append(
                f"3) 问题：按{first_date.displayName}看趋势\n"
                f"   SQL：SELECT DATE_FORMAT(`{first_date.columnName}`, '%Y-%m') AS dim_name, COUNT(1) AS metric_value FROM `table` GROUP BY DATE_FORMAT(`{first_date.columnName}`, '%Y-%m') ORDER BY dim_name LIMIT 30"
            )
    return "\n".join(examples) if examples else "暂无示例"


def choose_dimension(question: str, fields: list[FieldMeta]) -> FieldMeta:
    ranked = rank_fields(question, fields, preferred_type="TEXT")
    if ranked:
        return ranked[0]
    return first_by_type(fields, "TEXT") or fields[0]


def choose_metric(question: str, fields: list[FieldMeta]) -> FieldMeta | None:
    ranked = rank_fields(question, fields, preferred_type="NUMBER")
    if ranked:
        return ranked[0]
    return first_by_type(fields, "NUMBER")


def first_date_like_field(fields: list[FieldMeta]) -> FieldMeta | None:
    for field in fields:
        blob = " ".join([
            field.columnName or "",
            field.displayName or "",
            field.fieldComment or "",
        ]).lower()
        if any(token in blob for token in ["date", "time", "day", "month", "year", "日期", "时间", "创建", "下单", "订单"]):
            return field
    return None


def choose_chart_type(question: str, dimension: FieldMeta) -> str:
    if any(word in question for word in ["占比", "比例", "分类", "结构", "分布"]):
        return "pie"
    if dimension.fieldType == "DATE" or any(word in question for word in ["趋势", "变化", "每日", "月度", "年度", "季度"]):
        return "line"
    return "bar"


def first_matched(question: str, fields: list[FieldMeta], field_type: str) -> FieldMeta | None:
    for field in fields:
        if field.fieldType != field_type:
            continue
        names = [field.displayName, field.sourceFieldName or "", field.fieldComment or ""]
        if any(name and name in question for name in names):
            return field
    return None


def rank_fields(question: str, fields: list[FieldMeta], preferred_type: str | None = None) -> list[FieldMeta]:
    scored: list[tuple[int, FieldMeta]] = []
    for field in fields:
        score = score_field(question, field)
        if preferred_type and field.fieldType == preferred_type:
            score += 25
        elif preferred_type and preferred_type == "TEXT" and field.fieldType == "DATE":
            score += 10
        elif preferred_type and preferred_type == "NUMBER" and field.fieldType == "DATE":
            score -= 10
        scored.append((score, field))
    scored.sort(key=lambda item: item[0], reverse=True)
    return [field for score, field in scored if score > 0]


def score_field(question: str, field: FieldMeta) -> int:
    score = 0
    text = " ".join([
        field.columnName or "",
        field.displayName or "",
        field.sourceFieldName or "",
        field.fieldComment or "",
    ]).lower()
    q = question.lower()

    if field.fieldType == "DATE":
        score += 30 if any(term in q for term in ["趋势", "日期", "时间", "每日", "按月", "按年", "按周", "月份", "月度", "季度", "年度"]) else 0
    if field.fieldType == "NUMBER":
        score += 20 if any(term in q for term in ["销售", "金额", "收入", "营收", "利润", "数量", "销量", "总额", "成交", "订单数", "笔数"]) else 0
    if field.fieldType == "TEXT":
        score += 10 if any(term in q for term in ["省", "地区", "城市", "分类", "品类", "产品", "名称", "客户", "门店", "渠道"]) else 0

    semantic_pairs = [
        (["省份", "省", "省市", "地区"], ["province", "prov", "state", "region"]),
        (["城市", "市"], ["city"]),
        (["区域", "大区"], ["region", "area"]),
        (["销售额", "销售", "金额", "营收", "收入", "流水", "成交额", "总额"], ["sales", "sale", "amount", "amt", "revenue", "gmv", "total", "sum", "money"]),
        (["利润", "盈利", "毛利"], ["profit", "margin"]),
        (["数量", "销量", "件数", "订单数", "笔数"], ["qty", "quantity", "count", "volume", "order", "num"]),
        (["日期", "时间", "下单", "月份", "月度", "年度", "季度", "周", "天"], ["date", "time", "day", "month", "year", "quarter", "week"]),
        (["分类", "品类", "类型"], ["category", "type", "kind"]),
    ]
    for question_terms, keywords in semantic_pairs:
        if any(term in q for term in question_terms) and any(term in text for term in keywords):
            score += 40

    if field.displayName and field.displayName.lower() in q:
        score += 35
    if field.sourceFieldName and field.sourceFieldName.lower() in q:
        score += 30
    if field.columnName and field.columnName.lower() in q:
        score += 15
    if field.fieldComment and any(word in field.fieldComment.lower() for word in q.split() if word):
        score += 10

    if field.fieldType == "NUMBER" and any(term in text for term in ["count", "qty", "amount", "sales", "revenue", "profit", "price", "total"]):
        score += 10
    if field.fieldType == "TEXT" and any(term in text for term in ["province", "city", "region", "category", "name", "type"]):
        score += 10
    return score


def first_by_type(fields: list[FieldMeta], field_type: str) -> FieldMeta | None:
    return next((field for field in fields if field.fieldType == field_type), None)


def normalize_ai_sql_result(parsed: dict[str, Any], payload: TextToSqlRequest) -> dict[str, Any]:
    field_mapping = parsed.get("fieldMapping") if isinstance(parsed.get("fieldMapping"), dict) else {}
    sql = str(parsed.get("sql", ""))
    chart_type = str(parsed.get("chartType", "bar"))
    reasoning = parsed.get("reasoning") if isinstance(parsed.get("reasoning"), list) else []

    dimension_key = str(field_mapping.get("dimensionKey") or field_mapping.get("dimension") or "")
    metric_key = str(field_mapping.get("metricKey") or field_mapping.get("metric") or "")

    field_by_column = {field.columnName: field for field in payload.fields}
    dimension_field = field_by_column.get(dimension_key) or choose_dimension(payload.question, payload.fields)
    metric_field = field_by_column.get(metric_key) or choose_metric(payload.question, payload.fields)

    if dimension_field:
        dimension_key = dimension_field.columnName
    if metric_field:
        metric_key = metric_field.columnName

    if not dimension_key:
        dimension_key = choose_dimension(payload.question, payload.fields).columnName
    if not metric_key:
        metric_key = choose_metric(payload.question, payload.fields).columnName if choose_metric(payload.question, payload.fields) else "value"

    if " AS dim_name" not in sql and dimension_key:
        sql = rewrite_sql_alias(sql, dimension_key, metric_key)

    return {
        **parsed,
        "sql": sql,
        "chartType": chart_type,
        "fieldMapping": {
            "dimension": field_by_column.get(dimension_key).displayName if dimension_key in field_by_column else field_mapping.get("dimension", dimension_key),
            "metric": field_by_column.get(metric_key).displayName if metric_key in field_by_column else field_mapping.get("metric", metric_key),
            "dimensionKey": dimension_key,
            "metricKey": metric_key,
            "dimensionExpr": field_mapping.get("dimensionExpr", dimension_key),
        },
        "reasoning": reasoning,
    }


def rewrite_sql_alias(sql: str, dimension_key: str, metric_key: str) -> str:
    if not sql:
        return sql
    rewritten = sql
    rewritten = rewritten.replace(" AS name", " AS dim_name")
    rewritten = rewritten.replace(" AS value", " AS metric_value")
    rewritten = rewritten.replace(f" AS `{dimension_key}`", " AS dim_name")
    rewritten = rewritten.replace(f" AS `{metric_key}`", " AS metric_value")
    rewritten = rewritten.replace(" AS province", " AS dim_name")
    rewritten = rewritten.replace(" AS sales_amt", " AS metric_value")
    return rewritten
