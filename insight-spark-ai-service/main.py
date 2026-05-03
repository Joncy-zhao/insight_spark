from collections import defaultdict
from math import sqrt
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


app = FastAPI(title="Insight Spark AI Service", version="0.1.0")


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


class ChartRecommendRequest(BaseModel):
    columns: list[str] = []
    rows: list[dict[str, Any]] = []


class DiagnoseRequest(BaseModel):
    tableName: str
    metricField: str
    dimensionFields: list[str] = []
    timeField: str | None = None
    rows: list[dict[str, Any]] = []


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

    dimension = choose_dimension(payload.question, payload.fields)
    metric = choose_metric(payload.question, payload.fields)
    chart_type = choose_chart_type(payload.question, dimension)

    if metric:
        value_expr = f"SUM(CAST(NULLIF(`{metric.columnName}`, '') AS DECIMAL(18,2)))"
        metric_name = metric.displayName
    else:
        value_expr = "COUNT(1)"
        metric_name = "记录数"

    order_expr = "name ASC" if chart_type == "line" else "value DESC"
    sql = (
        f"SELECT `{dimension.columnName}` AS name, {value_expr} AS value "
        f"FROM `{payload.tableName}` "
        f"WHERE `{dimension.columnName}` IS NOT NULL AND `{dimension.columnName}` <> '' "
        f"GROUP BY `{dimension.columnName}` "
        f"ORDER BY {order_expr} LIMIT 30"
    )

    return {
        "sql": sql,
        "chartType": chart_type,
        "fieldMapping": {
            "dimension": dimension.displayName,
            "metric": metric_name,
        },
        "reasoning": [
            f"识别维度字段：{dimension.displayName}",
            f"识别指标字段：{metric_name}",
            f"推荐图表类型：{chart_type}",
        ],
    }


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
        "reportMarkdown": report_markdown,
    }


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
) -> str:
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
    return "\n".join(lines)


def choose_dimension(question: str, fields: list[FieldMeta]) -> FieldMeta:
    if any(word in question for word in ["趋势", "日期", "时间", "每日", "变化"]):
        date_field = first_by_type(fields, "DATE")
        if date_field:
            return date_field

    matched_text = first_matched(question, fields, "TEXT")
    if matched_text:
        return matched_text

    return first_by_type(fields, "TEXT") or fields[0]


def choose_metric(question: str, fields: list[FieldMeta]) -> FieldMeta | None:
    return first_matched(question, fields, "NUMBER") or first_by_type(fields, "NUMBER")


def choose_chart_type(question: str, dimension: FieldMeta) -> str:
    if any(word in question for word in ["占比", "比例", "分类", "结构"]):
        return "pie"
    if dimension.fieldType == "DATE" or any(word in question for word in ["趋势", "变化", "每日"]):
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


def first_by_type(fields: list[FieldMeta], field_type: str) -> FieldMeta | None:
    return next((field for field in fields if field.fieldType == field_type), None)
