from collections import defaultdict
from math import sqrt
import logging
from typing import Any
import base64
import json
import os
import re
import time
from urllib import error, request

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel


app = FastAPI(title="Insight Spark AI Service", version="0.1.0")
logger = logging.getLogger(__name__)


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
COMMERCIAL_API_KEY = os.getenv("COMMERCIAL_API_KEY", "").strip()
COMMERCIAL_MODEL = os.getenv("COMMERCIAL_MODEL", "").strip()
COMMERCIAL_BASE_URL = os.getenv("COMMERCIAL_BASE_URL", "").rstrip("/")
LOCAL_API_KEY = os.getenv("LOCAL_API_KEY", os.getenv("OLLAMA_API_KEY", "ollama")).strip()
LOCAL_MODEL = os.getenv("LOCAL_MODEL", os.getenv("OLLAMA_MODEL", "")).strip()
LOCAL_BASE_URL = os.getenv("LOCAL_BASE_URL", os.getenv("OLLAMA_BASE_URL", "")).rstrip("/")
TRANSLATION_MODEL = os.getenv("DASHSCOPE_TRANSLATION_MODEL", "qwen-mt-plus").strip()
TTS_API_KEY = os.getenv("DASHSCOPE_API_KEY", OPENAI_API_KEY).strip()
TTS_BASE_URL = os.getenv("DASHSCOPE_TTS_BASE_URL", "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation").strip()
TTS_MODEL = os.getenv("DASHSCOPE_TTS_MODEL", "qwen3-tts-flash-2025-09-18").strip()
TTS_MALE_VOICE = os.getenv("DASHSCOPE_TTS_MALE_VOICE", "Ethan").strip()
TTS_FEMALE_VOICE = os.getenv("DASHSCOPE_TTS_FEMALE_VOICE", "Cherry").strip()
REALTIME_TTS_BASE_URL = os.getenv("DASHSCOPE_REALTIME_TTS_BASE_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime").strip()
REALTIME_TTS_MODEL = os.getenv("DASHSCOPE_REALTIME_TTS_MODEL", "qwen3-tts-flash-realtime").strip()
REALTIME_TTS_MALE_VOICE = os.getenv("DASHSCOPE_REALTIME_TTS_MALE_VOICE", "Neil").strip()
REALTIME_TTS_FEMALE_VOICE = os.getenv("DASHSCOPE_REALTIME_TTS_FEMALE_VOICE", "Seren").strip()
TTS_CACHE_TTL_SECONDS = max(0, int(os.getenv("DASHSCOPE_TTS_CACHE_TTL_SECONDS", "600").strip() or "600"))
TTS_CACHE_MAX_SIZE = max(1, int(os.getenv("DASHSCOPE_TTS_CACHE_MAX_SIZE", "64").strip() or "64"))
TTS_URL_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}


class FieldMeta(BaseModel):
    sourceFieldName: str | None = None
    columnName: str
    fieldType: str = "TEXT"
    displayName: str
    fieldComment: str | None = None
    synonyms: str | None = None
    sensitive: bool | int | None = False
    sortOrder: int | None = None


class TextToSqlRequest(BaseModel):
    question: str
    tableName: str
    fields: list[FieldMeta]
    previewRows: list[dict[str, Any]] = []
    graphPath: dict[str, Any] = {}
    graphContext: list[dict[str, Any]] = []
    graphSqlHints: dict[str, Any] = {}
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


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
    fieldLabels: dict[str, str] = {}
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


class BusinessModelSemanticRequest(BaseModel):
    question: str
    requirement: str = ""
    tableName: str
    fields: list[FieldMeta]
    previewRows: list[dict[str, Any]] = []
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


class BusinessModelPatchRequest(BaseModel):
    question: str
    tableName: str
    modelName: str = ""
    modelRequirement: str = ""
    dictionaryEntries: list[dict[str, Any]] = []
    metricDefinitions: list[dict[str, Any]] = []
    dimensionSystem: list[dict[str, Any]] = []
    fields: list[FieldMeta]
    previewRows: list[dict[str, Any]] = []
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


class AdvancedAnalysisParseRequest(BaseModel):
    question: str
    tableName: str = ""
    context: dict[str, Any] = {}
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


class SmartChatRouteRequest(BaseModel):
    question: str
    tableName: str = ""
    context: dict[str, Any] = {}
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


class AdvancedAnalysisExplainRequest(BaseModel):
    type: str
    question: str = ""
    result: dict[str, Any] = {}
    context: dict[str, Any] = {}
    modelId: str = ""
    modelConfig: dict[str, Any] = {}
    temperature: float | int | None = None
    timeoutSeconds: int | None = None


class TtsRequest(BaseModel):
    text: str
    voiceGender: str = "female"
    locale: str = "zh-CN"
    voiceLocale: str | None = None
    rate: float = 1.0
    volume: float = 0.85


class AsrRequest(BaseModel):
    audioBase64: str
    locale: str = "en-US"


def normalize_asr_language(locale: str) -> str | None:
    normalized = str(locale or "").strip().lower()
    if not normalized:
        return None
    if normalized.startswith(("zh-hk", "yue", "cantonese", "hk")):
        return "yue"
    if normalized.startswith("en"):
        return "en"
    if normalized.startswith(("ja", "jp")):
        return "ja"
    return "zh"


def normalize_audio_data_uri(audio_value: str) -> str:
    content = str(audio_value or "").strip()
    if not content:
        return ""
    if content.startswith("data:"):
        return content
    return f"data:audio/wav;base64,{content}"


def extract_asr_text(response: dict[str, Any]) -> str:
    if not isinstance(response, dict):
        return ""

    candidate_choices = []
    choices = response.get("choices")
    if isinstance(choices, list):
        candidate_choices.append(choices)

    output = response.get("output")
    if isinstance(output, dict):
        output_choices = output.get("choices")
        if isinstance(output_choices, list):
            candidate_choices.append(output_choices)

    for choice_list in candidate_choices:
        for choice in choice_list:
            if not isinstance(choice, dict):
                continue
            message = choice.get("message") if isinstance(choice.get("message"), dict) else None
            delta = choice.get("delta") if isinstance(choice.get("delta"), dict) else None
            for content_node in (
                message.get("content") if isinstance(message, dict) else None,
                delta.get("content") if isinstance(delta, dict) else None,
            ):
                if isinstance(content_node, str):
                    text = content_node.strip()
                    if text:
                        return text
                if isinstance(content_node, list):
                    parts: list[str] = []
                    for item in content_node:
                        if isinstance(item, dict):
                            text = str(item.get("text") or item.get("content") or "").strip()
                            if text:
                                parts.append(text)
                    text = "".join(parts).strip()
                    if text:
                        return text
            if isinstance(message, dict):
                audio = message.get("audio")
                if isinstance(audio, dict):
                    transcript = str(audio.get("transcript") or audio.get("text") or "").strip()
                    if transcript:
                        return transcript
    return ""


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "models": list_llm_models()}


@app.get("/ai/models")
def ai_models() -> dict[str, Any]:
    return {"models": list_llm_models()}


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
        raise HTTPException(status_code=400, detail="当前数据表没有字段元信息，请先上传有效数据表。")

    graph_plan = resolve_graph_sql_plan(payload)

    if is_llm_payload_available(payload):
        ai_result = call_openai_text_to_sql(payload)
        if ai_result:
            if should_override_sql_with_graph_plan(str(ai_result.get("sql", "")), graph_plan, payload):
                guided = build_graph_guided_sql_result(payload, graph_plan, str(ai_result.get("chartType", "bar")))
                ai_result["sql"] = guided["sql"]
                ai_result["fieldMapping"] = guided["fieldMapping"]
                ai_result["chartType"] = guided["chartType"]
                reasoning = ai_result.get("reasoning")
                if isinstance(reasoning, list):
                    reasoning.append("图谱提示与模型输出存在偏差，已按图谱映射自动纠偏")
            if should_override_sql_with_semantic_plan(str(ai_result.get("sql", "")), ai_result, payload):
                guided = build_graph_guided_sql_result(payload, graph_plan, str(ai_result.get("chartType", "")))
                ai_result["sql"] = guided["sql"]
                ai_result["fieldMapping"] = guided["fieldMapping"]
                ai_result["chartType"] = guided["chartType"]
                reasoning = ai_result.get("reasoning")
                if isinstance(reasoning, list):
                    reasoning.append("模型 SQL 与语义计划不一致，已按 TopN/对比/时间/字段语义自动纠偏")
            ai_result["graphSqlHintsUsed"] = graph_plan["used"]
            ai_result["graphDecision"] = graph_plan["decision"]
            if graph_plan["used"] and isinstance(ai_result.get("reasoning"), list):
                ai_result["reasoning"].append("图谱提示已参与字段映射与歧义纠正")
            return ai_result

    guided = build_graph_guided_sql_result(payload, graph_plan, "")
    return {
        "sql": guided["sql"],
        "chartType": guided["chartType"],
        "fieldMapping": guided["fieldMapping"],
        "reasoning": guided["reasoning"],
        "graphSqlHintsUsed": graph_plan["used"],
        "graphDecision": graph_plan["decision"],
        "model": "rule-based-fallback",
    }


@app.post("/ai/business-model-semantic")
def business_model_semantic(payload: BusinessModelSemanticRequest) -> dict[str, Any]:
    requirement = (payload.requirement or payload.question or "").strip()
    if not requirement:
        raise HTTPException(status_code=400, detail="建模需求不能为空。")
    if not payload.fields:
        raise HTTPException(status_code=400, detail="当前数据表没有字段元信息，请先上传有效数据表。")

    if is_llm_payload_available(payload):
        ai_result = call_openai_business_model_semantic(payload)
        if ai_result:
            return normalize_business_model_semantic_result(ai_result, payload)

    return build_rule_based_business_model_semantic_result(payload)


@app.post("/ai/business-model-patch")
def business_model_patch(payload: BusinessModelPatchRequest) -> dict[str, Any]:
    question = (payload.question or "").strip()
    if not question:
        raise HTTPException(status_code=400, detail="模型修改指令不能为空。")
    if not payload.fields:
        raise HTTPException(status_code=400, detail="当前数据表没有字段元信息，无法执行模型修改。")

    if is_llm_payload_available(payload):
        ai_result = call_openai_business_model_patch(payload)
        if ai_result:
            return normalize_business_model_patch_result(ai_result, payload)

    return build_rule_based_business_model_patch_result(payload)


@app.post("/ai/advanced-analysis/parse")
def advanced_analysis_parse(payload: AdvancedAnalysisParseRequest) -> dict[str, Any]:
    question = (payload.question or "").strip()
    if not question:
        raise HTTPException(status_code=400, detail="高级分析问题不能为空。")
    if is_llm_payload_available(payload):
        ai_result = call_openai_advanced_analysis_parse(payload)
        if ai_result:
            return normalize_advanced_analysis_parse_result(ai_result, payload)
    return build_rule_based_advanced_analysis_parse_result(payload)


@app.post("/ai/smart-chat/route")
def smart_chat_route(payload: SmartChatRouteRequest) -> dict[str, Any]:
    question = (payload.question or "").strip()
    if not question:
        raise HTTPException(status_code=400, detail="智能路由问题不能为空。")
    if is_llm_payload_available(payload):
        ai_result = call_openai_smart_chat_route(payload)
        if ai_result:
            return normalize_smart_chat_route_result(ai_result, payload)
    return build_rule_based_smart_chat_route_result(payload)


@app.post("/ai/advanced-analysis/explain")
def advanced_analysis_explain(payload: AdvancedAnalysisExplainRequest) -> dict[str, Any]:
    analysis_type = normalize_advanced_intent(payload.type)
    if analysis_type == "none":
        raise HTTPException(status_code=400, detail="高级分析类型无效。")
    if not isinstance(payload.result, dict) or not payload.result:
        raise HTTPException(status_code=400, detail="缺少后端算法结果，无法生成解释。")
    if is_llm_payload_available(payload):
        ai_result = call_openai_advanced_analysis_explain(payload)
        if ai_result:
            return normalize_advanced_analysis_explain_result(ai_result, payload)
    return build_rule_based_advanced_analysis_explain_result(payload)


@app.post("/ai/tts")
def synthesize_speech(payload: TtsRequest) -> dict[str, Any]:
    text = str(payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="播报文本不能为空")
    if not TTS_API_KEY:
        raise HTTPException(status_code=503, detail="未配置云端 TTS API Key")

    try:
        return call_dashscope_tts(payload)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"云端 TTS 调用失败: {exc}") from exc


@app.post("/ai/tts-url")
def synthesize_speech_url(payload: TtsRequest) -> dict[str, Any]:
    text = str(payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="播报文本不能为空")
    if not TTS_API_KEY:
        raise HTTPException(status_code=503, detail="未配置云端 TTS API Key")

    try:
        return call_dashscope_tts_url(payload)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"云端 TTS 调用失败: {exc}") from exc

@app.post("/ai/tts-stream")
def synthesize_speech_stream(payload: TtsRequest) -> StreamingResponse:
    text = str(payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="播报文本不能为空")
    if not TTS_API_KEY:
        raise HTTPException(status_code=503, detail="未配置云端 TTS API Key")
    return StreamingResponse(
        stream_dashscope_realtime_tts(payload),
        media_type="audio/pcm",
        headers={
            "X-Audio-Format": "pcm_s16le",
            "X-Audio-Sample-Rate": "24000",
            "X-Audio-Channels": "1",
            "Cache-Control": "no-store",
        },
    )


@app.post("/ai/asr")
def recognize_speech(payload: AsrRequest) -> dict[str, Any]:
    audio_data = normalize_audio_data_uri(payload.audioBase64)
    if not audio_data:
        raise HTTPException(status_code=400, detail="音频不能为空")
    api_key = OPENAI_API_KEY or TTS_API_KEY
    if not api_key:
        raise HTTPException(status_code=503, detail="未配置云端 ASR API Key")

    request_body: dict[str, Any] = {
        "model": "qwen3-asr-flash",
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": audio_data,
                        },
                    }
                ],
            }
        ],
        "stream": False,
        "asr_options": {
            "enable_itn": False,
        },
    }
    language = normalize_asr_language(payload.locale)
    if language:
        request_body["asr_options"]["language"] = language

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }

    response = read_http_json(f"{OPENAI_BASE_URL}/chat/completions", headers, request_body)
    transcript = extract_asr_text(response)
    if not transcript:
        logger.warning("ASR response without transcript: keys=%s response=%s", list(response.keys()) if isinstance(response, dict) else type(response).__name__, response if isinstance(response, dict) else str(response))
        raise HTTPException(status_code=502, detail="云端语音识别未返回文本")
    return {"text": transcript, "locale": payload.locale}


def build_dimension_expression(question: str, dimension: FieldMeta) -> str:
    column = f"`{dimension.columnName}`"
    if dimension.fieldType == "DATE":
        return date_expression(column, question)
    if any(token in question for token in ["按月", "每月", "每个月", "月份", "月度"]):
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
        if any(token in question for token in ["按月", "每月", "每个月", "月份", "月度"]):
            return f"{column} IS NOT NULL"
        return f"{column} IS NOT NULL"
    return f"{column} IS NOT NULL AND {column} <> ''"


def date_expression(column: str, question: str) -> str:
    if any(token in question for token in ["按月", "每月", "每个月", "月份", "月度"]):
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
    graph_chain = build_graphrag_chain(payload, base, graph_nodes, graph_edges, doc_evidence)
    base["rootCauses"] = enrich_root_causes(
        base.get("rootCauses", []),
        doc_evidence,
        graph_nodes,
        graph_edges,
        payload.metricField,
        payload.fieldLabels,
        graph_chain,
    )
    base["summary"] = f"{base.get('summary', '')} 已结合 {len(doc_evidence)} 条文档证据和 {len(graph_nodes)} 个图谱节点进行 GraphRAG 根因推理。"
    base["evidence"] = doc_evidence
    base["docEvidence"] = doc_evidence
    base["graphRagEvidenceChain"] = graph_chain
    base["reasoningPath"] = graph_path
    base["graphPath"] = {"nodes": graph_nodes[:16], "edges": graph_edges[:32], "pathText": path_text}
    base["graphReasoningPath"] = path_text or " -> ".join(str(item.get("label") or item.get("sourceId") or item.get("nodeType")) for item in graph_path)
    base["confidence"] = round(max((cause.get("confidence", 0) for cause in base["rootCauses"]), default=0), 2)
    base["reasoningLogs"] = build_graphrag_reasoning_logs(
        base.get("reasoningLogs", []), graph_nodes, graph_edges, doc_evidence, graph_chain, base["rootCauses"], payload.anomalyType
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
            + build_graphrag_markdown(base["rootCauses"], base["graphReasoningPath"], doc_evidence, base["reasoningLogs"], graph_chain)
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
    field_labels: dict[str, str] | None = None,
    graph_chain: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    enriched = list(base_causes or [])
    metric_label = business_label(metric_field, field_labels or {})
    graph_confidence = min(0.92, 0.45 + len(graph_nodes) * 0.03 + len(graph_edges) * 0.01)
    doc_confidence = min(0.9, 0.5 + len(doc_evidence) * 0.07)
    if graph_nodes:
        labels = " -> ".join(str(node.get("label") or node.get("sourceId") or node.get("nodeType")) for node in graph_nodes[:5])
        enriched.append({
            "level": "MEDIUM" if graph_confidence < 0.75 else "HIGH",
            "causeType": "Neo4j 多跳路径关联根因",
            "impactField": metric_label,
            "evidence": f"Neo4j 图谱从「{metric_label}」扩展到相关表、字段、标签或历史报告节点：{labels}",
            "confidence": round(graph_confidence, 2),
        })
    if doc_evidence:
        top_doc = doc_evidence[0]
        enriched.append({
            "level": "MEDIUM" if doc_confidence < 0.75 else "HIGH",
            "causeType": "文档证据支持根因",
            "impactField": metric_label,
            "evidence": f"{top_doc.get('source')} 提到：{top_doc.get('text')}",
            "confidence": round(doc_confidence, 2),
        })
    if graph_chain:
        chain_text = " -> ".join(str(item.get("label") or item.get("hopType")) for item in graph_chain[:6])
        enriched.append({
            "level": "HIGH" if len(graph_chain) >= 5 else "MEDIUM",
            "causeType": "GraphRAG 多跳证据链融合根因",
            "impactField": metric_label,
            "evidence": f"证据链按异常指标、业务维度、Neo4j 图谱、文档证据和根因结论逐跳融合：{chain_text}",
            "confidence": min(0.91, 0.58 + len(graph_chain) * 0.045),
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


def business_label(field: str | None, field_labels: dict[str, str]) -> str:
    if not field:
        return ""
    return str(field_labels.get(field) or field)


def build_graphrag_chain(
    payload: GraphRagDiagnoseRequest,
    base: dict[str, Any],
    graph_nodes: list[dict[str, Any]],
    graph_edges: list[dict[str, Any]],
    doc_evidence: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    chain: list[dict[str, Any]] = []
    field_labels = payload.fieldLabels or {}
    metric_label = business_label(payload.metricField, field_labels)
    anomalies = base.get("anomalies") or []
    contributions = base.get("dimensionContributions") or []

    chain.append(make_chain_hop(
        1,
        "异常指标定位",
        metric_label,
        f"围绕「{metric_label}」扫描 {len(payload.queryRows or payload.rows)} 条记录，识别异常节点 {len(anomalies)} 个。",
        1.0,
    ))
    step = 2
    for contribution in contributions[:3]:
        dimension = business_label(str(contribution.get("dimensionField", "")), field_labels)
        top_items = contribution.get("topItems") or []
        top_text = "；".join(f"{item.get('name')}={item.get('value')}，占比{item.get('share')}%" for item in top_items[:3])
        chain.append(make_chain_hop(
            step,
            "业务维度下钻",
            dimension,
            top_text or f"对「{dimension}」完成贡献拆解。",
            0.86,
        ))
        step += 1
    if payload.timeField:
        time_label = business_label(payload.timeField, field_labels)
        chain.append(make_chain_hop(step, "时间窗口回溯", time_label, f"沿「{time_label}」对异常前后窗口进行回溯。", 0.82))
        step += 1
    if graph_nodes:
        graph_labels = " -> ".join(str(node.get("label") or node.get("sourceId") or node.get("nodeType")) for node in graph_nodes[:5])
        relation_types = "、".join(sorted({str(edge.get("relationType") or "RELATED") for edge in graph_edges[:8]}))
        chain.append(make_chain_hop(
            step,
            "Neo4j 图谱扩展",
            "图谱节点/关系",
            f"命中 {len(graph_nodes)} 个节点、{len(graph_edges)} 条关系；节点链路：{graph_labels}；关系类型：{relation_types or 'RELATED'}。",
            0.84 if graph_edges else 0.72,
        ))
        step += 1
    for evidence in doc_evidence[:3]:
        score = to_float(evidence.get("score")) or 0
        chain.append(make_chain_hop(
            step,
            "文档证据召回",
            str(evidence.get("source") or "知识文档"),
            str(evidence.get("text") or ""),
            min(0.88, 0.62 + score * 0.03) if score else 0.62,
        ))
        step += 1
    chain.append(make_chain_hop(
        step,
        "根因结论生成",
        "根因假设",
        f"融合 {len(graph_nodes)} 个图谱节点、{len(graph_edges)} 条关系、{len(doc_evidence)} 条文档证据后生成根因假设。",
        min(0.9, 0.58 + len(chain) * 0.04),
    ))
    return chain


def make_chain_hop(step: int, hop_type: str, label: str, detail: str, confidence: float) -> dict[str, Any]:
    return {
        "step": step,
        "hopType": hop_type,
        "label": label,
        "detail": detail,
        "confidence": round(confidence, 2),
    }


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
    graph_chain: list[dict[str, Any]],
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
            "title": "融合 GraphRAG 多跳证据链",
            "status": "completed",
            "detail": f"将异常指标、业务维度、Neo4j 图谱、文档证据融合为 {len(graph_chain)} 跳证据链。",
        },
        {
            "step": 7,
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
    graph_chain: list[dict[str, Any]] | None = None,
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
    lines.append("### GraphRAG 多跳证据链")
    if graph_chain:
        for item in graph_chain:
            lines.append(
                f"- Step {item.get('step')} {item.get('hopType')}：{item.get('label')}。{item.get('detail')} 置信度 {item.get('confidence')}"
            )
    else:
        lines.append("- 暂无多跳证据链。")
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
    model_config = resolve_llm_config(payload)
    if not is_llm_config_available(model_config):
        return None
    body = json.dumps({
        "model": model_config["model"],
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
        "temperature": model_config["temperature"],
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = payload_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if not isinstance(parsed, dict) or not parsed.get("sql"):
            return None
        parsed = normalize_ai_sql_result(parsed, payload)
        parsed.setdefault("model", model_config["model"])
        parsed.setdefault("modelId", model_config["id"])
        parsed.setdefault("modelName", model_config["name"])
        parsed.setdefault("provider", model_config["provider"])
        parsed.setdefault("reasoning", ["由大模型生成"])
        return parsed
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def list_llm_models() -> list[dict[str, Any]]:
    default_model_name = OPENAI_MODEL or "qwen-plus"
    models = []
    if OPENAI_API_KEY and OPENAI_MODEL and OPENAI_BASE_URL:
        models.append(
        {
            "id": "default",
            "name": default_model_name,
            "category": "CONFIGURED_DEFAULT",
            "provider": provider_label(OPENAI_BASE_URL),
            "model": default_model_name,
            "baseUrl": mask_base_url(OPENAI_BASE_URL),
            "available": bool(OPENAI_API_KEY),
            "note": "当前 .env 中 OPENAI_MODEL / OPENAI_BASE_URL 配置的默认模型",
        })
    if COMMERCIAL_API_KEY and COMMERCIAL_MODEL and COMMERCIAL_BASE_URL:
        models.append(
        {
            "id": "commercial-default",
            "name": COMMERCIAL_MODEL,
            "category": "CLOSED_COMMERCIAL",
            "provider": provider_label(COMMERCIAL_BASE_URL),
            "model": COMMERCIAL_MODEL,
            "baseUrl": mask_base_url(COMMERCIAL_BASE_URL),
            "available": bool(COMMERCIAL_API_KEY),
            "note": "COMMERCIAL_MODEL / COMMERCIAL_BASE_URL 配置的闭源商用模型",
        })
    if LOCAL_MODEL and LOCAL_BASE_URL:
        models.append(
        {
            "id": "local-private",
            "name": LOCAL_MODEL,
            "category": "LOCAL_PRIVATE",
            "provider": provider_label(LOCAL_BASE_URL),
            "model": LOCAL_MODEL,
            "baseUrl": mask_base_url(LOCAL_BASE_URL),
            "available": bool(LOCAL_BASE_URL),
            "note": "LOCAL_MODEL / LOCAL_BASE_URL 配置的本地私有化模型",
        })
    seen = set()
    unique_models = []
    for item in models:
        key = (item["model"], item["baseUrl"])
        if key in seen:
            continue
        seen.add(key)
        unique_models.append(item)
    return unique_models


def resolve_llm_config(payload: Any,
                       default_temperature: float = 0.1,
                       default_timeout_seconds: int = 25) -> dict[str, Any]:
    model_config = getattr(payload, "modelConfig", {})
    model_config = model_config if isinstance(model_config, dict) else {}
    context = getattr(payload, "context", {})
    context = context if isinstance(context, dict) else {}
    context_model_config = context.get("modelConfig") if isinstance(context.get("modelConfig"), dict) else {}
    requested_id = str(
        getattr(payload, "modelId", "")
        or model_config.get("modelId")
        or context.get("modelId")
        or context_model_config.get("modelId")
        or "default"
    ).strip()
    if requested_id in {"gpt-4", "openai-default", "qwen-plus", ""}:
        requested_id = "default"
    temperature = first_present(
        model_config.get("temperature"),
        getattr(payload, "temperature", None),
        context.get("temperature"),
        context_model_config.get("temperature"),
    )
    timeout_seconds = first_present(
        model_config.get("timeoutSeconds"),
        getattr(payload, "timeoutSeconds", None),
        context.get("timeoutSeconds"),
        context_model_config.get("timeoutSeconds"),
    )
    try:
        temperature_value = float(temperature if temperature is not None else default_temperature)
    except (TypeError, ValueError):
        temperature_value = default_temperature
    try:
        timeout_value = int(timeout_seconds if timeout_seconds is not None else default_timeout_seconds)
    except (TypeError, ValueError):
        timeout_value = default_timeout_seconds
    timeout_value = max(5, min(timeout_value, 120))
    if requested_id == "commercial-default":
        return llm_config(
            "commercial-default", COMMERCIAL_MODEL or OPENAI_MODEL, "闭源商用模型",
            COMMERCIAL_BASE_URL or OPENAI_BASE_URL, COMMERCIAL_API_KEY or OPENAI_API_KEY,
            temperature_value, timeout_value
        )
    if requested_id == "local-private":
        return llm_config(
            "local-private", LOCAL_MODEL, "本地私有化模型",
            LOCAL_BASE_URL, LOCAL_API_KEY or "ollama",
            temperature_value, timeout_value
        )
    return llm_config(
        "default", OPENAI_MODEL, OPENAI_MODEL or "默认模型",
        OPENAI_BASE_URL, OPENAI_API_KEY,
        temperature_value, timeout_value
    )


def first_present(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def is_llm_config_available(model_config: dict[str, Any]) -> bool:
    return bool(
        str(model_config.get("model") or "").strip()
        and str(model_config.get("baseUrl") or "").strip()
        and str(model_config.get("apiKey") or "").strip()
    )


def is_llm_payload_available(payload: Any) -> bool:
    return is_llm_config_available(resolve_llm_config(payload))


def llm_config(model_id: str, model: str, name: str, base_url: str, api_key: str,
               temperature: float, timeout_seconds: int) -> dict[str, Any]:
    return {
        "id": model_id,
        "model": model,
        "name": name or model,
        "baseUrl": base_url.rstrip("/"),
        "apiKey": api_key,
        "provider": provider_label(base_url),
        "temperature": temperature,
        "timeoutSeconds": timeout_seconds,
    }


def provider_label(base_url: str) -> str:
    lowered = (base_url or "").lower()
    if "localhost:11434" in lowered or "127.0.0.1:11434" in lowered:
        return "Ollama"
    if "dashscope" in lowered:
        return "DashScope"
    if "openai" in lowered:
        return "OpenAI"
    return "OpenAI-Compatible"


def mask_base_url(base_url: str) -> str:
    return (base_url or "").rstrip("/")


def call_openai_business_model_semantic(payload: BusinessModelSemanticRequest) -> dict[str, Any] | None:
    prompt = build_business_model_semantic_prompt(payload)
    model_config = resolve_llm_config(payload, default_temperature=0.1, default_timeout_seconds=30)
    if not is_llm_config_available(model_config):
        return None
    body = json.dumps({
        "model": model_config["model"],
        "messages": [
            {"role": "system", "content": (
                "你是企业级零代码业务建模专家。\n"
                "你的任务：根据用户的建模需求、字段元信息和样本数据，判断是否需要生成业务字典与业务公式。\n"
                "必须遵守：\n"
                "1. 只输出严格 JSON，不要输出解释性文本。\n"
                "2. 只有当用户语义里明确表达了字典/术语/同义词/映射需求时，才生成 dictionaryEntries。\n"
                "3. 只有当用户语义里明确表达了公式/指标/衍生指标/比率/计算口径需求时，才生成 metricDefinitions。\n"
                "4. 如果只是普通“创建模型”，dictionaryEntries 和 metricDefinitions 必须返回空数组。\n"
                "5. field 必须绑定到真实字段 columnName；formula 里尽量使用真实字段 columnName。\n"
                "6. 不确定时宁可少生成，不要臆造。"
            )},
            {"role": "user", "content": prompt},
        ],
        "temperature": model_config["temperature"],
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = payload_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return None
        parsed.setdefault("model", model_config["model"])
        parsed.setdefault("modelId", model_config["id"])
        parsed.setdefault("modelName", model_config["name"])
        parsed.setdefault("provider", model_config["provider"])
        parsed.setdefault("reasoning", ["由大模型完成业务模型语义拆解"])
        return parsed
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def call_openai_business_model_patch(payload: BusinessModelPatchRequest) -> dict[str, Any] | None:
    prompt = build_business_model_patch_prompt(payload)
    model_config = resolve_llm_config(payload, default_temperature=0.1, default_timeout_seconds=30)
    if not is_llm_config_available(model_config):
        return None
    body = json.dumps({
        "model": model_config["model"],
        "messages": [
            {"role": "system", "content": (
                "你是企业级零代码业务建模维护专家。\n"
                "你的任务：把用户对已有业务模型的自然语言修改要求，转换为可执行的结构化补丁。\n"
                "必须遵守：\n"
                "1. 只输出严格 JSON，不要输出解释性文本。\n"
                "2. intent 只允许 BIND_FIELDS 或 PATCH_MODEL。\n"
                "3. operation 只允许 targetType 为 dictionaryEntry、metricDefinition 或 fieldBinding。\n"
                "4. fieldBinding 用于“把某个业务名/指标名/维度名绑定到某个真实字段”的语义修正。\n"
                "5. action 只允许 UPSERT 或 DELETE。\n"
                "6. field 必须尽量绑定到真实字段 columnName，formula 尽量使用真实字段 columnName。\n"
                "7. 如果无法识别到明确修改动作，返回空 operations，不要臆造。\n"
                "8. 支持从一句话里识别多条字典映射、多条公式修改和多条字段绑定。"
            )},
            {"role": "user", "content": prompt},
        ],
        "temperature": model_config["temperature"],
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = payload_json["choices"][0]["message"]["content"]
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return None
        parsed.setdefault("model", model_config["model"])
        parsed.setdefault("modelId", model_config["id"])
        parsed.setdefault("modelName", model_config["name"])
        parsed.setdefault("provider", model_config["provider"])
        parsed.setdefault("reasoning", ["由大模型完成业务模型修改语义拆解"])
        return parsed
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def call_openai_advanced_analysis_parse(payload: AdvancedAnalysisParseRequest) -> dict[str, Any] | None:
    model_config = resolve_llm_config(payload, default_temperature=0.1, default_timeout_seconds=10)
    if not is_llm_config_available(model_config):
        return None
    prompt = (
        "请把用户自然语言解析为预测与情景模拟模块的前端参数。\n"
        "只输出严格 JSON，不要输出 Markdown 或解释文本。\n"
        "intent 只能是 forecast、whatIf、alert 或 none。\n"
        "forecast 需要尽量给出 metric、horizon(7d/30d/3m/6m)、algorithm(Prophet/Holt-Winters)、confidence。\n"
        "如果上下文提供 fields/timeFields/numericFields，请优先返回真实 columnName：timeField、metricField、targetMetricField。\n"
        "whatIf 需要给出 metric、variables 数组，变量项包含 name 与 change，change 表示百分比变化，可为负数。\n"
        "whatIf 的 variables 如能匹配真实字段，请给出 field。\n"
        "whatIf 只有用户显式写出“公式/按/按照”、等号或 SAFE_DIVIDE/IF 等函数表达式时才返回 formula；单纯“某指标提升/下降 x%”不是公式，formula 必须为空。\n"
        "formula 只保留等号右侧或纯四则/函数表达式，禁止包含“推演/预测/变化”等后续自然语言。\n"
        "alert 需要给出 metric、operator(lt/gt/zscore)、threshold 数值、channel(email/dingtalk/both)。\n"
        f"用户问题：{payload.question}\n"
        f"当前数据源：{payload.tableName}\n"
        f"上下文：{json.dumps(payload.context or {}, ensure_ascii=False)}"
    )
    body = json.dumps({
        "model": model_config["model"],
        "messages": [
            {"role": "system", "content": "你是企业 BI 预测、情景推演和预警意图解析器。必须输出可被 json.loads 解析的 JSON。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": model_config["temperature"],
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = str(payload_json["choices"][0]["message"]["content"]).strip()
        if content.startswith("```"):
            content = re.sub(r"^```(?:json)?\s*", "", content)
            content = re.sub(r"\s*```$", "", content)
        parsed = json.loads(content)
        if isinstance(parsed, dict):
            parsed.setdefault("model", model_config["model"])
            parsed.setdefault("modelId", model_config["id"])
            parsed.setdefault("modelName", model_config["name"])
            parsed.setdefault("provider", model_config["provider"])
        return parsed if isinstance(parsed, dict) else None
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def call_openai_smart_chat_route(payload: SmartChatRouteRequest) -> dict[str, Any] | None:
    prompt = build_smart_chat_route_prompt(payload)
    model_config = resolve_llm_config(payload, default_temperature=0.05, default_timeout_seconds=20)
    if not is_llm_config_available(model_config):
        return None
    body = json.dumps({
        "model": model_config["model"],
        "messages": [
            {"role": "system", "content": (
                "你是企业 BI 对话入口的全局语义路由器。\n"
                "你必须先理解用户真实动作意图，再选择唯一 primaryIntent。\n"
                "只输出严格 JSON，不要 Markdown，不要解释性前后缀。\n"
                "不要因为出现“以后、未来、刚才、趋势、提醒”等单个词就机械触发预测或预警；必须结合整句动作。\n"
                "涉及创建预警、权限、发布、邀请等有副作用操作时 requiresConfirmation 必须为 true。"
            )},
            {"role": "user", "content": prompt},
        ],
        "temperature": model_config["temperature"],
        "max_tokens": 900,
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = str(payload_json["choices"][0]["message"]["content"]).strip()
        if content.startswith("```"):
            content = re.sub(r"^```(?:json)?\s*", "", content)
            content = re.sub(r"\s*```$", "", content)
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return None
        parsed.setdefault("model", model_config["model"])
        parsed.setdefault("modelId", model_config["id"])
        parsed.setdefault("modelName", model_config["name"])
        parsed.setdefault("provider", model_config["provider"])
        return parsed
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def call_openai_advanced_analysis_explain(payload: AdvancedAnalysisExplainRequest) -> dict[str, Any] | None:
    prompt = build_advanced_analysis_explain_prompt(payload)
    model_config = resolve_llm_config(payload, default_temperature=0.2, default_timeout_seconds=75)
    if not is_llm_config_available(model_config):
        return None
    body = json.dumps({
        "model": model_config["model"],
        "messages": [
            {"role": "system", "content": (
                "你是企业 BI 预测、情景推演和预警解释助手。"
                "只输出严格 JSON，不要 Markdown。"
                "只能解释后端结果，禁止重新计算或编造数值。"
            )},
            {"role": "user", "content": prompt},
        ],
        "temperature": model_config["temperature"],
        "max_tokens": 650,
    }).encode("utf-8")

    req = request.Request(
        f"{model_config['baseUrl']}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {model_config['apiKey']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=model_config["timeoutSeconds"]) as resp:
            payload_json = json.loads(resp.read().decode("utf-8"))
        content = str(payload_json["choices"][0]["message"]["content"]).strip()
        if content.startswith("```"):
            content = re.sub(r"^```(?:json)?\s*", "", content)
            content = re.sub(r"\s*```$", "", content)
        parsed = json.loads(content)
        if isinstance(parsed, dict):
            parsed.setdefault("model", model_config["model"])
            parsed.setdefault("modelId", model_config["id"])
            parsed.setdefault("modelName", model_config["name"])
            parsed.setdefault("provider", model_config["provider"])
        return parsed if isinstance(parsed, dict) else None
    except (error.URLError, error.HTTPError, KeyError, ValueError, json.JSONDecodeError):
        return None


def normalize_advanced_intent(value: Any) -> str:
    text = str(value or "").strip()
    if text in {"forecast", "timeSeriesForecast", "prediction"}:
        return "forecast"
    if text in {"whatIf", "simulation", "scenario"}:
        return "whatIf"
    if text in {"alert", "warning", "anomaly"}:
        return "alert"
    return "none"


SMART_CHAT_INTENTS = {
    "QUERY_SQL",
    "FORECAST",
    "ALERT_RULE_CREATE",
    "WHAT_IF",
    "BUSINESS_MODEL_CREATE",
    "BUSINESS_MODEL_PATCH",
    "BUSINESS_MODEL_APPLY",
    "BUSINESS_MODEL_PUBLISH",
    "DASHBOARD_PIN",
    "DASHBOARD_CREATE",
    "CHART_RULE_UPDATE",
    "FIELD_SEMANTIC_FIX",
    "FEDERATED_QUERY",
    "PERMISSION_POLICY_CREATE",
    "AUDIT_QUERY",
    "REPORT_GENERATE",
    "TASK_STATUS_QUERY",
    "COLLABORATION_INVITE",
    "CLARIFY",
}


def build_smart_chat_route_prompt(payload: SmartChatRouteRequest) -> str:
    context = payload.context if isinstance(payload.context, dict) else {}
    fields = context.get("fields") if isinstance(context.get("fields"), list) else []
    compact_fields = []
    for item in fields[:80]:
        if not isinstance(item, dict):
            continue
        compact_fields.append({
            "columnName": item.get("columnName"),
            "displayName": item.get("displayName"),
            "fieldType": item.get("fieldType"),
            "fieldComment": item.get("fieldComment"),
            "synonyms": item.get("synonyms"),
        })
    return (
        f"用户输入：{payload.question}\n"
        f"当前数据源：{payload.tableName or '未指定'}\n"
        f"字段摘要：{json.dumps(compact_fields, ensure_ascii=False)}\n\n"
        "请输出严格 JSON：\n"
        "{\n"
        '  "primaryIntent": "",\n'
        '  "confidence": 0.0,\n'
        '  "requiresConfirmation": false,\n'
        '  "slots": {},\n'
        '  "missingSlots": [],\n'
        '  "reasoning": "",\n'
        '  "executionMode": "DIRECT | DRAFT | CLARIFY"\n'
        "}\n\n"
        "primaryIntent 只能从以下枚举中选择：\n"
        "QUERY_SQL, FORECAST, ALERT_RULE_CREATE, WHAT_IF, BUSINESS_MODEL_CREATE, BUSINESS_MODEL_PATCH, "
        "BUSINESS_MODEL_APPLY, BUSINESS_MODEL_PUBLISH, DASHBOARD_PIN, DASHBOARD_CREATE, CHART_RULE_UPDATE, "
        "FIELD_SEMANTIC_FIX, FEDERATED_QUERY, PERMISSION_POLICY_CREATE, AUDIT_QUERY, REPORT_GENERATE, "
        "TASK_STATUS_QUERY, COLLABORATION_INVITE, CLARIFY。\n\n"
        "分类准则：\n"
        "1. 查询排名、分组、明细、占比、对比、分布、汇总，选 QUERY_SQL；不要附带预测。\n"
        "2. 只有用户明确要求预测、预估未来数值、未来走势外推，才选 FORECAST。\n"
        "3. 创建阈值提醒、异常通知、告警规则，选 ALERT_RULE_CREATE 且 requiresConfirmation=true。\n"
        "4. 假设变量变化并测算结果，选 WHAT_IF；普通查询中的“增长/下降”不等于 WHAT_IF。\n"
        "5. 字段绑定、术语映射、业务字典、指标口径、公式、以后按某口径计算，选 BUSINESS_MODEL_PATCH。\n"
        "6. 新建业务模型，选 BUSINESS_MODEL_CREATE；套用/发布模型分别选 APPLY/PUBLISH。\n"
        "7. 把图表保存、钉到、加入看板，选 DASHBOARD_PIN；新建看板选 DASHBOARD_CREATE。\n"
        "8. 权限、发布、协作邀请、预警创建等有副作用动作必须先生成草稿或澄清。\n"
        "9. 若多个意图混合，选择用户最主要的业务动作，并在 slots.secondaryIntents 中列出次要意图。\n"
        "10. 不确定时选 CLARIFY 或低 confidence，不要强行执行。\n\n"
        "容易混淆的语义边界：\n"
        "- “以后销售额就按含税收入算”是指标口径/建模修改，不是预测。\n"
        "- “把刚才这个图钉到销售看板”是看板资产操作，不是预测。\n"
        "- “看一下各省销售额排名”是查询排名，不是预测。\n"
        "- “把销售额绑定到 sales_amt”是字段绑定/业务字典维护，不是普通查询。\n\n"
        "slots 要尽量抽取字段：metricField、timeField、dimensionField、threshold、operator、horizon、dashboardName、businessTerm、physicalField。"
    )


def normalize_smart_chat_intent(value: Any) -> str:
    raw = str(value or "").strip().upper().replace("-", "_")
    aliases = {
        "QUERY": "QUERY_SQL",
        "SQL": "QUERY_SQL",
        "TEXT_TO_SQL": "QUERY_SQL",
        "PREDICTION": "FORECAST",
        "TIME_SERIES_FORECAST": "FORECAST",
        "ALERT": "ALERT_RULE_CREATE",
        "WARNING": "ALERT_RULE_CREATE",
        "WHATIF": "WHAT_IF",
        "SIMULATION": "WHAT_IF",
        "SCENARIO": "WHAT_IF",
        "BUSINESS_MODEL": "BUSINESS_MODEL_PATCH",
        "MODEL_PATCH": "BUSINESS_MODEL_PATCH",
        "DASHBOARD": "DASHBOARD_PIN",
    }
    intent = aliases.get(raw, raw)
    return intent if intent in SMART_CHAT_INTENTS else "CLARIFY"


def normalize_smart_chat_route_result(parsed: dict[str, Any], payload: SmartChatRouteRequest) -> dict[str, Any]:
    intent = normalize_smart_chat_intent(parsed.get("primaryIntent") or parsed.get("intent") or parsed.get("type"))
    if has_alert_semantics(payload.question):
        intent = "ALERT_RULE_CREATE"
    slots = parsed.get("slots") if isinstance(parsed.get("slots"), dict) else {}
    missing = parsed.get("missingSlots") if isinstance(parsed.get("missingSlots"), list) else []
    confidence = read_float(parsed.get("confidence"))
    if confidence <= 0:
        confidence = 0.55 if intent != "CLARIFY" else 0.35
    requires_confirmation = as_bool(parsed.get("requiresConfirmation"))
    if intent in {"ALERT_RULE_CREATE", "PERMISSION_POLICY_CREATE", "BUSINESS_MODEL_PUBLISH",
                  "COLLABORATION_INVITE", "DASHBOARD_PIN", "DASHBOARD_CREATE"}:
        requires_confirmation = True
    if intent == "QUERY_SQL":
        slots = {**slots, "autoForecastEnabled": False}
    reasoning = str(parsed.get("reasoning") or parsed.get("reason") or "").strip()
    if not reasoning:
        reasoning = f"全局语义路由识别为 {intent}"
    return {
        "primaryIntent": intent,
        "confidence": max(0.0, min(confidence, 1.0)),
        "requiresConfirmation": requires_confirmation,
        "slots": slots,
        "missingSlots": [str(item) for item in missing if str(item).strip()][:8],
        "reasoning": reasoning,
        "executionMode": str(parsed.get("executionMode") or ("DRAFT" if requires_confirmation else "DIRECT")).strip(),
        "model": parsed.get("model") or OPENAI_MODEL,
        "fallbackUsed": bool(parsed.get("fallbackUsed", False)),
    }


def has_alert_semantics(question: str) -> bool:
    q = str(question or "").strip().lower()
    if not q:
        return False
    has_notify_action = re.search(r"提醒|通知|预警|告警|警报|alert|warning|钉钉|邮件", q, re.I) is not None
    has_threshold_condition = re.search(r"低于|高于|超过|跌破|小于|大于|以下|以上|阈值|异常|z-?score", q, re.I) is not None
    return has_notify_action and has_threshold_condition


def normalize_advanced_analysis_parse_result(parsed: dict[str, Any], payload: AdvancedAnalysisParseRequest) -> dict[str, Any]:
    intent = normalize_advanced_intent(parsed.get("intent") or parsed.get("type"))
    explicit_formula_intent = has_explicit_advanced_formula_intent(payload.question)
    parsed_formula = normalize_advanced_formula(parsed.get("formula") or parsed.get("businessFormula") or "") if explicit_formula_intent else ""
    result = {
        "intent": intent,
        "metric": str(parsed.get("metric") or parsed.get("targetMetric") or "").strip(),
        "timeField": str(parsed.get("timeField") or "").strip(),
        "metricField": str(parsed.get("metricField") or "").strip(),
        "targetMetricField": str(parsed.get("targetMetricField") or "").strip(),
        "granularity": str(parsed.get("granularity") or "").strip(),
        "horizon": str(parsed.get("horizon") or "").strip(),
        "algorithm": str(parsed.get("algorithm") or "").strip(),
        "confidence": str(parsed.get("confidence") or "").strip(),
        "operator": str(parsed.get("operator") or "").strip(),
        "threshold": parsed.get("threshold"),
        "channel": str(parsed.get("channel") or "").strip(),
        "formula": parsed_formula,
        "variables": parsed.get("variables") if isinstance(parsed.get("variables"), list) else [],
        "reasoning": parsed.get("reasoning") or "由大模型解析预测/推演/预警意图",
        "model": parsed.get("model") or OPENAI_MODEL,
        "fallbackUsed": False,
    }
    if not result["metric"]:
        result["metric"] = infer_advanced_metric(payload.question, payload.context)
    if intent == "forecast":
        result["horizon"] = result["horizon"] or infer_advanced_horizon(payload.question)
        result["algorithm"] = result["algorithm"] or "Prophet"
        result["confidence"] = result["confidence"] or "95%"
    elif intent == "alert":
        result["operator"] = normalize_advanced_operator(result["operator"], payload.question)
        result["threshold"] = normalize_advanced_threshold(result["threshold"], payload.question)
        result["channel"] = result["channel"] if result["channel"] in {"email", "dingtalk", "both"} else "both"
    elif intent == "whatIf":
        result["variables"] = normalize_advanced_variables(result["variables"], payload.question)
        result["formula"] = result["formula"] or infer_advanced_formula(payload.question)
    return result


def normalize_text_list(value: Any, max_items: int = 5) -> list[str]:
    if isinstance(value, list):
        rows = [str(item or "").strip() for item in value]
    else:
        rows = [str(value or "").strip()] if value else []
    return [item for item in rows if item][:max_items]


def normalize_advanced_analysis_explain_result(parsed: dict[str, Any], payload: AdvancedAnalysisExplainRequest) -> dict[str, Any]:
    calculation = normalize_text_list(
        parsed.get("calculation") or parsed.get("calculationResults") or parsed.get("algorithmResults"),
        5,
    )
    suggestions = normalize_text_list(
        parsed.get("suggestions") or parsed.get("recommendations") or parsed.get("aiSuggestions"),
        5,
    )
    fallback = build_rule_based_advanced_analysis_explain_result(payload)
    if not calculation:
        calculation = fallback["calculation"]
    if not suggestions:
        suggestions = fallback["suggestions"]
    return {
        "source": "llm",
        "sourceLabel": "AI 解释",
        "calculation": calculation,
        "suggestions": suggestions,
        "guardrail": "explanation-only",
        "model": parsed.get("model") or OPENAI_MODEL,
        "reasoning": parsed.get("reasoning") or "基于后端算法结果生成自然语言解释",
    }


def build_rule_based_advanced_analysis_parse_result(payload: AdvancedAnalysisParseRequest) -> dict[str, Any]:
    question = payload.question or ""
    lowered = question.lower()
    intent = "none"
    if re.search(r"预警|提醒|告警|低于|高于|超过|跌破|异常|阈值|通知|钉钉|邮件|z-?score", lowered):
        intent = "alert"
    elif re.search(r"预测|预估|未来|走势外推|forecast|prophet|holt", lowered):
        intent = "forecast"
    elif re.search(r"what-?if|如果|若|假设|提升|下降|降低|增长|推演|模拟|利润变化", lowered):
        intent = "whatIf"
    result = {
        "intent": intent,
        "metric": infer_advanced_metric(question, payload.context),
        "fallbackUsed": True,
        "reasoning": "未调用大模型或大模型解析失败，已使用规则兜底",
    }
    if intent == "forecast":
        result.update({"horizon": infer_advanced_horizon(question), "algorithm": "Prophet", "confidence": "95%"})
    elif intent == "whatIf":
        result["variables"] = normalize_advanced_variables([], question)
        result["formula"] = infer_advanced_formula(question)
    elif intent == "alert":
        result.update({
            "operator": normalize_advanced_operator("", question),
            "threshold": normalize_advanced_threshold(None, question),
            "channel": "both",
        })
    return result


def build_rule_based_smart_chat_route_result(payload: SmartChatRouteRequest) -> dict[str, Any]:
    question = str(payload.question or "").strip()
    lowered = question.lower()
    intent = "QUERY_SQL"
    requires_confirmation = False
    slots: dict[str, Any] = {}
    reasoning = "AI 总路由不可用，已使用保守语义兜底"

    query_words = r"看一下|查看|查询|统计|排名|排行|top|明细|列表|分布|占比|对比|汇总|各省|各市|各区域|各部门"
    model_words = r"业务模型|建模|模型|业务字典|字典|术语|同义词|字段绑定|绑定到|绑定为|映射到|映射为|对应到|对应为|口径|公式|算作|当作|按.*算"
    dashboard_words = r"看板|仪表盘|大屏|驾驶舱"
    pin_words = r"钉|固定|保存|放到|放入|加入|添加|挂到"
    forecast_words = r"预测|预估|推算|估一下|大概会|会到多少|还会继续|未来(?:\d+|一|二|三|四|五|六|七|八|九|十|下个|下月|下季度|下半年|一年)|下个月|下季度|走势外推|forecast|prediction"
    alert_words = r"预警|告警|警报|提醒|通知|低于|高于|超过|跌破|阈值|异常|alert|warning"
    what_if_words = r"what-?if|如果|若|假设|推演|模拟|测算"

    if re.search(model_words, question, re.I):
        intent = "BUSINESS_MODEL_CREATE" if re.search(r"创建|新建|生成|建立|搭建", question) else "BUSINESS_MODEL_PATCH"
        physical = re.search(r"[A-Za-z_][A-Za-z0-9_]*", question)
        if physical:
            slots["physicalField"] = physical.group(0)
        reasoning += "，识别为业务模型维护动作"
    elif re.search(dashboard_words, question, re.I) and re.search(pin_words + r"|新建|创建", question, re.I):
        intent = "DASHBOARD_CREATE" if re.search(r"新建|创建|生成", question) else "DASHBOARD_PIN"
        requires_confirmation = True
        reasoning += "，识别为看板资产动作"
    elif re.search(query_words, question, re.I) and not re.search(forecast_words + r"|" + alert_words + r"|" + what_if_words, question, re.I):
        intent = "QUERY_SQL"
        slots["autoForecastEnabled"] = False
        reasoning += "，识别为查数/图表查询"
    elif re.search(alert_words, question, re.I):
        intent = "ALERT_RULE_CREATE"
        requires_confirmation = True
        reasoning += "，识别为预警规则草稿"
    elif re.search(forecast_words, question, re.I):
        intent = "FORECAST"
        reasoning += "，识别为明确预测请求"
    elif re.search(what_if_words, question, re.I):
        intent = "WHAT_IF"
        requires_confirmation = True
        reasoning += "，识别为情景推演草稿"
    elif re.search(r"权限|授权|角色|只能看|开放给", question):
        intent = "PERMISSION_POLICY_CREATE"
        requires_confirmation = True
        reasoning += "，识别为权限策略草稿"
    elif re.search(r"审计|危险查询|慢查询|拦截", question):
        intent = "AUDIT_QUERY"
        reasoning += "，识别为审计查询"
    elif re.search(r"诊断|报告|原因分析|下降原因|归因", question):
        intent = "REPORT_GENERATE"
        requires_confirmation = True
        reasoning += "，识别为报告生成草稿"

    confidence = 0.6 if intent != "QUERY_SQL" else 0.58
    return {
        "primaryIntent": intent,
        "confidence": confidence,
        "requiresConfirmation": requires_confirmation,
        "slots": slots,
        "missingSlots": [],
        "reasoning": reasoning,
        "executionMode": "DRAFT" if requires_confirmation else "DIRECT",
        "model": "rule-based-smart-route-fallback",
        "fallbackUsed": True,
    }


def normalize_advanced_formula(value: Any) -> str:
    formula = str(value or "").strip().replace("＝", "=").replace("（", "(").replace("）", ")")
    if not formula:
        return ""
    formula = formula.replace("，", ",")
    formula = re.sub(r"\bSAFE[\s-]+DIVIDE\b", "SAFE_DIVIDE", formula, flags=re.IGNORECASE)
    formula = re.sub(r"[。；;、]", " ", formula)
    formula = re.sub(r"\s*,\s*", ", ", formula)
    formula = re.sub(r"\s+", " ", formula).strip()
    for word in ["推演", "预测", "变化", "会怎么", "会怎样", "怎么办", "结果", "分析", "测算", "模拟", "如果", "若", "假设", "并", "请"]:
        index = formula.find(word)
        if index > 0:
            formula = formula[:index].strip()
    formula = re.sub(r"\s*(提升|增长|上涨|下降|降低|减少)\s*$", "", formula).strip()
    if "=" in formula:
        formula = formula.split("=", 1)[1].strip()
    return formula


def has_explicit_advanced_formula_intent(question: str) -> bool:
    text = str(question or "").strip().replace("＝", "=").replace("（", "(").replace("）", ")")
    text = re.sub(r"\s+", " ", text)
    if not text:
        return False
    function_pattern = r"\b(?:SAFE_DIVIDE|IF|ABS|MIN|MAX|ROUND|DIVIDE)\s*\("
    if re.search(function_pattern, text, re.IGNORECASE):
        return True
    if re.search(r"(?:业务公式|指标公式|公式|按|按照)\s*[:：]?.+[=]", text):
        return True
    if re.search(r"(?:按|按照)\s+.+[+\-*/].+", text):
        return True
    direct_match = re.search(r"([\u4e00-\u9fa5A-Za-z_][\u4e00-\u9fa5A-Za-z0-9_\s]*)=(.+)$", text)
    if not direct_match:
        return False
    right_expression = direct_match.group(2).strip()
    return bool(
        re.search(r"[+\-*/()]", right_expression)
        or re.fullmatch(r"[+-]?\d+(?:\.\d+)?%?", right_expression)
        or re.search(function_pattern, right_expression, re.IGNORECASE)
    )


def infer_advanced_formula(question: str) -> str:
    text = str(question or "").strip()
    if not has_explicit_advanced_formula_intent(text):
        return ""
    match = re.search(r"(?:公式|按|按照)\s*[:：]?(.+?[=＝].+)$", text)
    if match:
        return normalize_advanced_formula(match.group(1))
    function_match = re.search(r"\b(?:SAFE_DIVIDE|IF|ABS|MIN|MAX|ROUND|DIVIDE)\s*\(.+\)", text, re.IGNORECASE)
    if function_match:
        return normalize_advanced_formula(function_match.group(0))
    expression_match = re.search(r"(?:按|按照)\s+(.+[+\-*/].+)$", text)
    if expression_match:
        return normalize_advanced_formula(expression_match.group(1))
    direct_match = re.search(r"([\u4e00-\u9fa5A-Za-z_][\u4e00-\u9fa5A-Za-z0-9_\s]*)[=＝](.+)$", text)
    if direct_match:
        return normalize_advanced_formula(direct_match.group(0))
    return ""


def build_rule_based_advanced_analysis_explain_result(payload: AdvancedAnalysisExplainRequest) -> dict[str, Any]:
    result = payload.result if isinstance(payload.result, dict) else {}
    existing = result.get("explanation")
    if isinstance(existing, dict):
        calculation = normalize_text_list(
            existing.get("calculation") or existing.get("calculationResults") or existing.get("algorithmResults"),
            5,
        )
        suggestions = normalize_text_list(
            existing.get("suggestions") or existing.get("recommendations") or existing.get("aiSuggestions"),
            5,
        )
        if calculation or suggestions:
            return {
                "source": "rule",
                "sourceLabel": "规则解释",
                "calculation": calculation,
                "suggestions": suggestions,
                "guardrail": "explanation-only",
                "model": "rule-based-fallback",
            }

    analysis_type = normalize_advanced_intent(payload.type)
    if analysis_type == "forecast":
        return build_rule_based_forecast_explain(result)
    if analysis_type == "whatIf":
        return build_rule_based_what_if_explain(result)
    return build_rule_based_alert_explain(result)


def build_rule_based_forecast_explain(result: dict[str, Any]) -> dict[str, Any]:
    rows = result.get("series") if isinstance(result.get("series"), list) else []
    history_rows = [item for item in rows if isinstance(item, dict) and item.get("history") is not None]
    forecast_rows = [item for item in rows if isinstance(item, dict) and item.get("forecast") is not None]
    last_forecast = forecast_rows[-1].get("forecast") if forecast_rows else None
    algorithm = result.get("algorithm") or result.get("params", {}).get("algorithm") if isinstance(result.get("params"), dict) else ""
    calculation = [
        f"当前基于后端{algorithm or '预测算法'}结果生成解释，历史点数 {len(history_rows)}，预测点数 {len(forecast_rows)}。",
    ]
    if last_forecast is not None:
        calculation.append(f"末期预测值为 {last_forecast}，请结合置信区间上下界判断不确定性。")
    return {
        "source": "rule",
        "sourceLabel": "规则解释",
        "calculation": calculation,
        "suggestions": [
            "建议优先核对预测趋势和最近业务动作是否一致。",
            "如果置信区间较宽，应以保守方案安排预算、库存或运营资源。",
        ],
        "guardrail": "explanation-only",
        "model": "rule-based-fallback",
    }


def build_rule_based_what_if_explain(result: dict[str, Any]) -> dict[str, Any]:
    rows = result.get("series") if isinstance(result.get("series"), list) else []
    base = next((item.get("value") for item in rows if isinstance(item, dict) and item.get("name") == "基准方案"), None)
    recommended = next((item.get("value") for item in rows if isinstance(item, dict) and item.get("name") == "推荐方案"), None)
    formula = str(result.get("formula") or result.get("params", {}).get("formula") if isinstance(result.get("params"), dict) else "").strip()
    calculation = ["当前解释基于后端 What-if 推演结果，数值未由 AI 重新计算。"]
    if base is not None or recommended is not None:
        calculation.append(f"基准方案为 {base if base is not None else '-'}，推荐方案为 {recommended if recommended is not None else '-'}。")
    if formula:
        calculation.append(f"本次推演使用业务公式「{formula}」作为计算口径。")
    return {
        "source": "rule",
        "sourceLabel": "规则解释",
        "calculation": calculation,
        "suggestions": [
            "建议先检查推荐方案变量是否真实可控，再评估执行成本。",
            "推演结果用于比较方案，不应直接等同于因果结论。",
        ],
        "guardrail": "explanation-only",
        "model": "rule-based-fallback",
    }


def build_rule_based_alert_explain(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params") if isinstance(result.get("params"), dict) else {}
    event = result.get("event") if isinstance(result.get("event"), dict) else result
    operator = str(params.get("operator") or event.get("operator") or "").strip()
    threshold = params.get("threshold", event.get("threshold"))
    reason = str(event.get("reason") or result.get("reason") or "").strip()
    calculation = ["当前解释基于后端阈值/Z-Score 预警判断结果，异常结论未由 AI 重新判断。"]
    if operator or threshold is not None:
        calculation.append(f"预警条件为 {operator or '-'}，阈值为 {threshold if threshold is not None else '-'}。")
    if reason:
        calculation.append(f"触发原因：{reason}")
    return {
        "source": "rule",
        "sourceLabel": "规则解释",
        "calculation": calculation,
        "suggestions": [
            "建议先核对触发时间桶的原始数据和过滤口径。",
            "处理完成后可在预警事件中记录确认、关闭或重开备注。",
        ],
        "guardrail": "explanation-only",
        "model": "rule-based-fallback",
    }


def compact_json_text(value: Any, max_length: int = 5000) -> str:
    try:
        text = json.dumps(value, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = str(value)
    if len(text) <= max_length:
        return text
    return f"{text[:max_length]}...(已截断)"


def build_advanced_analysis_explain_prompt(payload: AdvancedAnalysisExplainRequest) -> str:
    analysis_type = normalize_advanced_intent(payload.type)
    type_label = {
        "forecast": "时序预测",
        "whatIf": "What-if 情景推演",
        "alert": "离线智能预警",
    }.get(analysis_type, "高级分析")
    return (
        f"分析类型：{type_label}\n"
        f"用户问题或方案标题：{payload.question or '未提供'}\n"
        f"上下文：{compact_json_text(payload.context or {}, 500)}\n"
        f"后端算法结果：{compact_json_text(payload.result or {}, 1800)}\n\n"
        "只基于后端算法结果生成业务可读解释，输出严格 JSON：\n"
        "{\n"
        '  "calculation": ["计算说明1", "计算说明2"],\n'
        '  "suggestions": ["建议1", "建议2"],\n'
        '  "reasoning": "简短说明解释依据"\n'
        "}\n"
        "要求：calculation 2-3 条，suggestions 2-3 条；每条不超过 45 字；不存在的数值不要编造。"
    )


def infer_advanced_metric(question: str, context: dict[str, Any] | None = None) -> str:
    for candidate in ["销售额", "利润", "成本", "销量", "收入", "转化率", "退货率", "客单价"]:
        if candidate in question:
            return candidate
    if isinstance(context, dict):
        return str(context.get("lastMetric") or "").strip()
    return ""


def infer_advanced_horizon(question: str) -> str:
    if re.search(r"6\s*个?月|半年", question):
        return "6m"
    if re.search(r"3\s*个?月|季度", question):
        return "3m"
    if re.search(r"30\s*天|一个月|1\s*个?月", question):
        return "30d"
    if re.search(r"7\s*天|一周|1\s*周", question):
        return "7d"
    return "3m"


def normalize_advanced_operator(value: Any, question: str) -> str:
    text = str(value or "").strip()
    if text in {"lt", "gt", "zscore"}:
        return text
    if re.search(r"高于|超过|大于", question):
        return "gt"
    if re.search(r"异常|z-?score", question, re.I):
        return "zscore"
    return "lt"


def normalize_advanced_threshold(value: Any, question: str) -> float:
    try:
        number = float(value)
        if number >= 0:
            return number
    except (TypeError, ValueError):
        pass
    match = re.search(r"(\d+(?:\.\d+)?)\s*(万|千|k|w)?", question, re.I)
    if not match:
        return 100000
    number = float(match.group(1))
    unit = (match.group(2) or "").lower()
    if unit in {"万", "w"}:
        return number * 10000
    if unit in {"千", "k"}:
        return number * 1000
    return number


def normalize_advanced_variables(items: list[Any], question: str) -> list[dict[str, Any]]:
    variables: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or item.get("variable") or item.get("label") or "").strip()
        try:
            change = float(item.get("change", item.get("changePercent", item.get("delta", 0))))
        except (TypeError, ValueError):
            continue
        if name:
            variables.append({"name": name, "change": change})
    if variables:
        return variables
    for name, direction, raw in re.findall(r"([\u4e00-\u9fa5A-Za-z]+?)(提升|增长|上涨|下降|降低|减少)\s*(\d+(?:\.\d+)?)\s*%", question):
        value = float(raw)
        if direction in {"下降", "降低", "减少"}:
            value = -value
        variables.append({"name": re.sub(r"[如果若假设]", "", name).strip() or "变量", "change": value})
    return variables or [{"name": "销量", "change": 10}, {"name": "成本", "change": -5}]


def build_business_model_semantic_prompt(payload: BusinessModelSemanticRequest) -> str:
    fields_text = "\n".join(
        f"- columnName={field.columnName}, displayName={field.displayName}, fieldType={field.fieldType}, fieldComment={field.fieldComment or ''}, synonyms={field.synonyms or ''}"
        for field in payload.fields
    )
    preview_text = "\n".join(
        f"- {json.dumps(row, ensure_ascii=False)}" for row in payload.previewRows[:5]
    ) or "暂无预览样本"
    return (
        f"用户原始需求：{payload.question}\n"
        f"当前建模需求：{payload.requirement or payload.question}\n"
        f"目标表：{payload.tableName}\n"
        f"字段信息：\n{fields_text}\n\n"
        f"预览样本：\n{preview_text}\n\n"
        "请输出严格 JSON，格式如下：\n"
        "{\n"
        '  "requirement": "",\n'
        '  "modelName": "",\n'
        '  "dictionaryEntries": [{"term": "", "field": "", "synonyms": ""}],\n'
        '  "metricDefinitions": [{"name": "", "field": "", "aggregation": "SUM|COUNT|AVG|MAX|MIN", "formula": ""}],\n'
        '  "reasoning": ["", ""],\n'
        '  "confidence": 0.0\n'
        "}\n"
        "规则：\n"
        "0. modelName 必须是根据用户真实业务主题提炼出的简短模型名，通常 6 到 14 个中文字符，不要照抄整句需求，不要包含“基于当前表”“帮我”“创建一个”等动作描述。\n"
        "1. 如果用户明确写了“业务字典/字典/同义词/映射/术语”，就抽取 dictionaryEntries。\n"
        "2. 如果用户明确写了“业务公式/指标公式/新增公式/利润率/转化率/同比/环比/核心指标包含”，就抽取 metricDefinitions。\n"
        "3. 如果用户只是在描述分析目标，没有明确要求字典/公式，则对应数组返回空数组。\n"
        "4. 支持中文逗号、顿号、分号分隔的多条词条/多条公式。\n"
        "5. field 请尽量绑定到真实 columnName，例如 sales_amt、profit、qty、region。\n"
        "6. formula 请使用真实 columnName，例如 profit / sales_amt。"
    )


def build_business_model_patch_prompt(payload: BusinessModelPatchRequest) -> str:
    fields_text = "\n".join(
        f"- columnName={field.columnName}, displayName={field.displayName}, fieldType={field.fieldType}, fieldComment={field.fieldComment or ''}, synonyms={field.synonyms or ''}"
        for field in payload.fields
    )
    preview_text = "\n".join(
        f"- {json.dumps(row, ensure_ascii=False)}" for row in payload.previewRows[:5]
    ) or "暂无预览样本"
    dictionary_text = json.dumps(payload.dictionaryEntries[:20], ensure_ascii=False)
    metric_text = json.dumps(payload.metricDefinitions[:20], ensure_ascii=False)
    dimension_text = json.dumps(payload.dimensionSystem[:20], ensure_ascii=False)
    return (
        f"用户修改指令：{payload.question}\n"
        f"当前模型名：{payload.modelName}\n"
        f"当前模型需求：{payload.modelRequirement}\n"
        f"当前模型字典：{dictionary_text}\n"
        f"当前模型公式：{metric_text}\n"
        f"当前模型维度：{dimension_text}\n"
        f"目标表：{payload.tableName}\n"
        f"字段信息：\n{fields_text}\n\n"
        f"预览样本：\n{preview_text}\n\n"
        "请输出严格 JSON，格式如下：\n"
        "{\n"
        '  "intent": "BIND_FIELDS | PATCH_MODEL",\n'
        '  "operations": [\n'
        '    {"semanticAction": "FIELD_BINDING|METRIC_FORMULA_UPDATE|METRIC_SCOPE_UPDATE|DICTIONARY_UPSERT|DIMENSION_BINDING", "targetType": "metricDefinition", "action": "UPSERT", "name": "", "field": "", "aggregation": "SUM|COUNT|AVG|MAX|MIN", "formula": ""},\n'
        '    {"semanticAction": "DICTIONARY_UPSERT", "targetType": "dictionaryEntry", "action": "UPSERT", "term": "", "field": "", "synonyms": ""},\n'
        '    {"semanticAction": "FIELD_BINDING|DIMENSION_BINDING", "targetType": "fieldBinding", "action": "UPSERT", "bindingType": "AUTO|dictionaryEntry|metricDefinition|dimensionDefinition", "name": "", "field": ""}\n'
        "  ],\n"
        '  "reasoning": ["", ""],\n'
        '  "confidence": 0.0\n'
        "}\n"
        "规则：\n"
        "1. 新增或修改指标/公式，输出 targetType=metricDefinition。\n"
        "2. 新增或修改业务字典/术语映射，输出 targetType=dictionaryEntry。\n"
        "3. 如果用户是在修正字段绑定，例如“把销售额绑定到sales_amt”“将省份维度对应到province”，优先输出 targetType=fieldBinding，并把 intent 设为 BIND_FIELDS。\n"
        "4. bindingType 用于提示目标类型，可选 dictionaryEntry、metricDefinition、dimensionDefinition，无法确定时填 AUTO。\n"
        "5. 用户表达“口径/以后/统一用/统一按/按…算/以后报表里…”时，优先输出 semanticAction=METRIC_SCOPE_UPDATE 或 METRIC_FORMULA_UPDATE，targetType=metricDefinition，不要输出 dictionaryEntry 或普通 fieldBinding。\n"
        "6. 用户只提到某一个指标，例如“收入”，operations 只能修改该指标；不得联想修改“销售额、省份”等未提到对象。\n"
        "7. 用户没有提到维度，不得生成维度操作。\n"
        "8. 如果“含税金额”等口径来源对应多个字段或无法确定，operations 返回空数组，并在 reasoning 中说明需要用户确认字段。\n"
        "9. 如果用户表达“删除/移除/去掉”，对应 action=DELETE。\n"
        "10. 修改已有公式时，name 必须尽量对齐已有指标名。\n"
        "11. 一句话中可能包含多条操作，要全部拆开。\n"
        "12. 若没有可执行修改动作，operations 返回空数组。"
    )


def normalize_business_model_semantic_result(parsed: dict[str, Any], payload: BusinessModelSemanticRequest) -> dict[str, Any]:
    requirement = str(parsed.get("requirement") or payload.requirement or payload.question).strip()
    model_name = infer_business_model_name(
        str(parsed.get("modelName") or ""),
        requirement,
        payload.question,
    )
    dictionary_entries = normalize_dictionary_entries(parsed.get("dictionaryEntries"), payload.fields)
    metric_definitions = normalize_metric_definitions(parsed.get("metricDefinitions"), payload.fields)
    reasoning = parsed.get("reasoning") if isinstance(parsed.get("reasoning"), list) else []
    confidence = read_float(parsed.get("confidence"))
    return {
        "requirement": requirement,
        "modelName": model_name,
        "dictionaryEntries": dictionary_entries,
        "metricDefinitions": metric_definitions,
        "reasoning": [str(item) for item in reasoning if str(item).strip()][:6],
        "confidence": confidence,
        "model": parsed.get("model") or OPENAI_MODEL,
    }


def normalize_business_model_patch_result(parsed: dict[str, Any], payload: BusinessModelPatchRequest) -> dict[str, Any]:
    operations = normalize_patch_operations(parsed.get("operations"), payload.fields)
    reasoning = parsed.get("reasoning") if isinstance(parsed.get("reasoning"), list) else []
    confidence = read_float(parsed.get("confidence"))
    intent = infer_patch_intent(parsed.get("intent"), operations)
    return {
        "intent": intent,
        "operations": operations,
        "reasoning": [str(item) for item in reasoning if str(item).strip()][:8],
        "confidence": confidence,
        "model": parsed.get("model") or OPENAI_MODEL,
    }


def build_rule_based_business_model_semantic_result(payload: BusinessModelSemanticRequest) -> dict[str, Any]:
    requirement = (payload.requirement or payload.question).strip()
    dictionary_entries: list[dict[str, Any]] = []
    metric_definitions: list[dict[str, Any]] = []
    reasoning = ["AI 语义拆解不可用，已使用保守规则兜底"]

    if has_dictionary_intent(payload.question):
        dictionary_entries = build_dictionary_entries_from_question(payload.question, payload.fields)
        if dictionary_entries:
            reasoning.append(f"识别到 {len(dictionary_entries)} 条业务字典映射")

    if has_formula_intent(payload.question):
        metric_definitions = build_metric_entries_from_question(payload.question, payload.fields)
        if metric_definitions:
            reasoning.append(f"识别到 {len(metric_definitions)} 条业务公式/指标定义")

    return {
        "requirement": requirement,
        "modelName": infer_business_model_name("", requirement, payload.question),
        "dictionaryEntries": dictionary_entries,
        "metricDefinitions": metric_definitions,
        "reasoning": reasoning,
        "confidence": 0.45 if dictionary_entries or metric_definitions else 0.35,
        "model": "rule-based-business-model-fallback",
    }


def build_rule_based_business_model_patch_result(payload: BusinessModelPatchRequest) -> dict[str, Any]:
    operations = build_patch_operations_from_question(
        payload.question,
        payload.fields,
        payload.dictionaryEntries,
        payload.metricDefinitions,
        payload.dimensionSystem,
    )
    reasoning = ["AI 模型修改语义拆解不可用，已使用规则兜底"]
    if operations:
        reasoning.append(f"识别到 {len(operations)} 条模型修改操作")
    else:
        reasoning.append("未识别到明确的字典或公式修改动作")
    intent = infer_patch_intent("BIND_FIELDS" if has_binding_intent(payload.question) else "PATCH_MODEL", operations)
    return {
        "intent": intent,
        "operations": operations,
        "reasoning": reasoning,
        "confidence": 0.52 if operations else 0.3,
        "model": "rule-based-business-model-patch-fallback",
    }


def has_binding_intent(question: str) -> bool:
    q = (question or "").strip()
    if not q:
        return False
    if any(token in q for token in ["字段绑定", "绑定字段", "字段修正", "改绑", "重新绑定"]):
        return True
    bind_verbs = ["绑定到", "绑定为", "绑定至", "映射到", "映射为", "映射至", "对应到", "对应为", "对应至", "改成", "改为", "修改为", "更新为"]
    bind_targets = ["字段", "指标", "公式", "维度", "术语", "字典", "同义词", "模型"]
    if any(token in q for token in bind_verbs) and any(token in q for token in bind_targets):
        return True
    return any(token in q for token in bind_verbs) and bool(re.search(r"[A-Za-z_][A-Za-z0-9_]*", q))


def infer_patch_intent(raw_intent: Any, operations: list[dict[str, Any]]) -> str:
    intent = str(raw_intent or "").strip().upper()
    if intent == "BIND_FIELDS":
        return "BIND_FIELDS"
    has_field_binding = any(str(item.get("targetType") or "").strip() == "fieldBinding" for item in operations)
    has_non_binding = any(str(item.get("targetType") or "").strip() != "fieldBinding" for item in operations)
    if has_field_binding and not has_non_binding:
        return "BIND_FIELDS"
    return "PATCH_MODEL"


def infer_business_model_name(ai_name: str, requirement: str, question: str) -> str:
    candidate = clean_business_model_name(ai_name)
    if candidate:
        return ensure_model_suffix(candidate)

    for source in [requirement, question]:
        candidate = extract_business_subject(source)
        if candidate:
            return ensure_model_suffix(candidate)

    return "零代码业务模型"


def extract_business_subject(text: str) -> str:
    source = str(text or "").strip()
    if not source:
        return ""

    source = re.sub(r"[\r\n\t]+", " ", source)
    source = re.sub(r"[“”\"'`<>]", "", source)
    source = re.sub(r"\s+", " ", source).strip()

    if not source:
        return ""

    patterns = [
        r"(?:创建|生成|新建|建立|搭建|构建|做一个|建一个)([^，。；;\n]*?模型)",
        r"(?:做|建|搭建|构建)([^，。；;\n]*?分析)",
        r"([一-龥A-Za-z0-9_]{2,18}(?:分析|画像|看板|专题|经营|运营|复购|生命周期))(?:模型)?",
    ]
    for pattern in patterns:
        matched = re.search(pattern, source, re.IGNORECASE)
        if matched:
            candidate = clean_business_model_name(matched.group(1))
            if candidate:
                return candidate

    candidate = clean_business_model_name(source)
    return candidate


def clean_business_model_name(text: str) -> str:
    value = str(text or "").strip()
    if not value:
        return ""

    value = re.sub(r"[\r\n\t]+", " ", value)
    value = re.sub(r"[“”\"'`<>]", "", value)
    value = re.sub(r"\s+", " ", value).strip()

    value = re.sub(r"^(请|请你|帮我|麻烦|需要|我想|想要|帮忙)+", "", value)
    value = re.sub(r"^基于(?:当前|现有)?[^，。；;\n]*?(?:表|数据源)?", "", value)
    value = re.sub(r"^(围绕|针对|面向)", "", value)
    value = re.sub(r"(创建|生成|新建|建立|搭建|构建)(一个|一份|个)?", "", value)
    value = re.sub(r"(业务字典|字典|同义词|术语|映射|新增指标公式|增加指标公式|添加指标公式|指标公式|业务公式|公式)\s*[：:].*", "", value)
    value = re.sub(r"(并|然后|之后).*$", "", value)
    value = re.sub(r"(需求|场景|内容|能力)$", "", value)
    value = re.sub(r"^[,，;；。:：\\-\\s]+", "", value)
    value = re.sub(r"[,，;；。:：\\-\\s]+$", "", value).strip()

    replacements = [
        ("当前", ""),
        ("销售明细表", ""),
        ("数据表", ""),
        ("明细表", ""),
        ("数据源", ""),
        ("业务模型", ""),
        ("模型搭建", ""),
        ("进行", ""),
        ("一个", ""),
    ]
    for old, new in replacements:
        value = value.replace(old, new)

    value = value.strip()
    value = re.sub(r"^(基于|按照|按|对|将)", "", value).strip()

    if value.endswith("模型模型"):
        value = value[:-2]
    if len(value) > 16:
        value = value[:16].strip()
    return value


def ensure_model_suffix(text: str) -> str:
    value = str(text or "").strip()
    if not value:
        return "零代码业务模型"
    if not value.endswith("模型"):
        value = f"{value}模型"
    return value[:18].strip()


def build_text_to_sql_prompt(payload: TextToSqlRequest) -> str:
    fields_text = "\n".join(
        f"- columnName={field.columnName}, displayName={field.displayName}, fieldType={field.fieldType}, fieldComment={field.fieldComment or ''}"
        for field in payload.fields
    )
    preview_text = "\n".join(
        f"- {json.dumps(row, ensure_ascii=False)}" for row in payload.previewRows[:5]
    ) or "暂无预览样本"
    examples = get_prompt_examples(payload.fields)
    graph_hint_text = build_graph_hint_prompt(payload)
    return (
        f"用户问题：{payload.question}\n"
        f"目标表：{payload.tableName}\n"
        f"字段信息：\n{fields_text}\n\n"
        f"预览样本：\n{preview_text}\n\n"
        f"图谱映射提示：\n{graph_hint_text}\n\n"
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
        "如果图谱映射提示提供了推荐维度/指标/业务公式，优先按该提示生成字段映射和聚合表达式；若冲突，需要在 reasoning 说明。"
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


def field_text_blob(field: FieldMeta) -> str:
    return " ".join([
        field.columnName or "",
        field.displayName or "",
        field.sourceFieldName or "",
        field.fieldComment or "",
    ]).lower()


def has_any_term(text: str, terms: list[str]) -> bool:
    return any(term and term in text for term in terms)


def is_id_like_field(field: FieldMeta) -> bool:
    blob = " ".join([
        field.columnName or "",
        field.displayName or "",
        field.sourceFieldName or "",
    ]).lower()
    if has_any_term(blob, ["rows_id", "row_id", "order_id", "user_id", "cust_id", "uuid", "编号", "编码"]):
        return True
    return bool(re.search(r"(^|[_\s-])id($|[_\s-])", blob))


def wants_region_dimension(question: str) -> bool:
    q = question.lower()
    return has_any_term(q, ["省份", "省市", "省区", "按省", "地区", "区域", "大区", "省", "华东", "华南", "华北", "华中", "中南", "西南", "西北", "东北"])


def has_macro_region_value(question: str) -> bool:
    return has_any_term(str(question or ""), ["华东", "华南", "华北", "华中", "中南", "西南", "西北", "东北", "东南"])


def wants_city_dimension(question: str) -> bool:
    q = question.lower()
    return has_any_term(q, ["城市", "各城", "按城市", "城市排行", "城市排名"])


def wants_amount_metric(question: str) -> bool:
    q = question.lower()
    return has_any_term(q, ["销售额", "销售金额", "销售", "营收", "收入", "金额", "成交额", "流水", "总额", "gmv"])


def wants_count_metric(question: str) -> bool:
    q = question.lower()
    return has_any_term(q, ["数量", "销量", "件数", "订单数", "笔数", "记录数", "count", "多少单", "多少笔"])


def wants_time_dimension(question: str) -> bool:
    q = question.lower()
    return has_any_term(q, ["趋势", "变化", "日期", "时间", "按月", "每月", "每个月", "按年", "按周", "按天", "月份", "月度", "季度", "年度", "下单日期", "每日"])


def region_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["province", "prov", "state", "region", "area", "省", "省份", "地区", "区域", "大区"])


def macro_region_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["region", "area", "zone", "district", "地区", "区域", "大区"]) \
        and not has_any_term(text, ["province", "prov", "省份", "省"])


def city_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["city", "城市", "市"])


def time_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["date", "time", "day", "month", "year", "quarter", "week", "日期", "时间", "下单", "订单时间", "创建时间"])


def amount_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["sales_amt", "salesamount", "sales", "sale", "amount", "amt", "revenue", "gmv", "money", "金额", "销售额", "营收", "收入", "成交额"])


def count_field_match(field: FieldMeta) -> bool:
    text = field_text_blob(field)
    return has_any_term(text, ["count", "qty", "quantity", "volume", "num", "rows", "订单数", "笔数", "数量", "销量", "件数"])


def is_dimension_semantic_mismatch(question: str, field: FieldMeta) -> bool:
    if wants_city_dimension(question):
        return not city_field_match(field)
    if wants_region_dimension(question):
        if has_macro_region_value(question) and macro_region_field_match(field):
            return False
        if has_macro_region_value(question) and not macro_region_field_match(field):
            return True
        if region_field_match(field):
            return False
        return True
    return False


def is_metric_semantic_mismatch(question: str, field: FieldMeta) -> bool:
    if wants_amount_metric(question):
        if field.fieldType != "NUMBER":
            return True
        if amount_field_match(field):
            return False
        if is_id_like_field(field):
            return True
        if count_field_match(field):
            return True
    return False


def choose_dimension(question: str, fields: list[FieldMeta], preferred: FieldMeta | None = None) -> FieldMeta:
    ranked_with_score = rank_fields_with_score(question, fields, preferred_type="TEXT")
    non_number_ranked = [(score, field) for score, field in ranked_with_score if field.fieldType != "NUMBER"]

    if preferred and preferred.fieldType != "NUMBER" and not is_dimension_semantic_mismatch(question, preferred):
        preferred_score = score_field_with_type_preference(question, preferred, "TEXT")
        best_score = non_number_ranked[0][0] if non_number_ranked else (ranked_with_score[0][0] if ranked_with_score else -10**6)
        if preferred_score + 10 >= best_score:
            return preferred

    if wants_time_dimension(question):
        date_ranked = [(score, field) for score, field in non_number_ranked if field.fieldType == "DATE" or time_field_match(field)]
        if date_ranked:
            return date_ranked[0][1]
        date_fallback = first_date_like_field([field for field in fields if field.fieldType != "NUMBER"])
        if date_fallback:
            return date_fallback

    if wants_city_dimension(question):
        city_ranked = [(score, field) for score, field in non_number_ranked if city_field_match(field)]
        if city_ranked:
            return city_ranked[0][1]

    if has_macro_region_value(question):
        macro_region_ranked = [(score, field) for score, field in non_number_ranked if macro_region_field_match(field)]
        if macro_region_ranked:
            return macro_region_ranked[0][1]

    if wants_region_dimension(question):
        region_ranked = [(score, field) for score, field in non_number_ranked if region_field_match(field)]
        if region_ranked:
            return region_ranked[0][1]

    if non_number_ranked:
        return non_number_ranked[0][1]
    if ranked_with_score:
        return ranked_with_score[0][1]
    return first_by_type(fields, "TEXT") or first_by_type(fields, "DATE") or fields[0]


def choose_metric(question: str, fields: list[FieldMeta], preferred: FieldMeta | None = None) -> FieldMeta | None:
    ranked_with_score = rank_fields_with_score(question, fields, preferred_type="NUMBER")
    number_ranked = [(score, field) for score, field in ranked_with_score if field.fieldType == "NUMBER"]

    if preferred and preferred.fieldType == "NUMBER" and not is_metric_semantic_mismatch(question, preferred):
        preferred_score = score_field_with_type_preference(question, preferred, "NUMBER")
        best_score = number_ranked[0][0] if number_ranked else (ranked_with_score[0][0] if ranked_with_score else -10**6)
        if preferred_score + 10 >= best_score:
            return preferred

    if wants_amount_metric(question):
        amount_ranked = [(score, field) for score, field in number_ranked if amount_field_match(field)]
        if amount_ranked:
            return amount_ranked[0][1]

    if wants_count_metric(question):
        count_ranked = [(score, field) for score, field in number_ranked if count_field_match(field) and not amount_field_match(field)]
        if count_ranked:
            return count_ranked[0][1]

    if number_ranked:
        return number_ranked[0][1]
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
    if dimension.fieldType == "DATE" or any(word in question for word in ["趋势", "变化", "每日", "每月", "每个月", "月度", "年度", "季度"]):
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
    return [field for score, field in rank_fields_with_score(question, fields, preferred_type)]


def rank_fields_with_score(question: str, fields: list[FieldMeta], preferred_type: str | None = None) -> list[tuple[int, FieldMeta]]:
    scored: list[tuple[int, FieldMeta]] = []
    for field in fields:
        score = score_field_with_type_preference(question, field, preferred_type)
        scored.append((score, field))
    scored.sort(key=lambda item: item[0], reverse=True)
    return [(score, field) for score, field in scored if score > 0]


def score_field_with_type_preference(question: str, field: FieldMeta, preferred_type: str | None = None) -> int:
    score = score_field(question, field)
    if preferred_type and field.fieldType == preferred_type:
        score += 25
    elif preferred_type and preferred_type == "TEXT" and field.fieldType == "DATE":
        score += 10
    elif preferred_type and preferred_type == "NUMBER" and field.fieldType == "DATE":
        score -= 10
    return score


def score_field(question: str, field: FieldMeta) -> int:
    score = 0
    text = field_text_blob(field)
    q = question.lower()

    if field.fieldType == "DATE":
        score += 30 if any(term in q for term in ["趋势", "日期", "时间", "每日", "每月", "每个月", "按月", "按年", "按周", "月份", "月度", "季度", "年度"]) else 0
    if field.fieldType == "NUMBER":
        score += 20 if any(term in q for term in ["销售", "金额", "收入", "营收", "利润", "数量", "销量", "总额", "成交", "订单数", "笔数"]) else 0
    if field.fieldType == "TEXT":
        score += 10 if any(term in q for term in ["省", "地区", "城市", "分类", "品类", "产品", "名称", "客户", "门店", "渠道"]) else 0

    if wants_city_dimension(question) and city_field_match(field):
        score += 95
    if has_macro_region_value(question) and macro_region_field_match(field):
        score += 120
    if has_macro_region_value(question) and region_field_match(field) and not macro_region_field_match(field):
        score -= 60
    if wants_region_dimension(question) and region_field_match(field):
        score += 85
    if wants_amount_metric(question) and amount_field_match(field):
        score += 95
    if wants_count_metric(question) and count_field_match(field):
        score += 70

    if wants_region_dimension(question) and is_id_like_field(field):
        score -= 70
    if wants_amount_metric(question) and field.fieldType == "NUMBER" and is_id_like_field(field):
        score -= 80
    if wants_amount_metric(question) and count_field_match(field):
        score -= 35
    if wants_count_metric(question) and amount_field_match(field):
        score -= 35

    semantic_pairs = [
        (["省份", "省", "省市", "省区", "地区", "区域"], ["province", "prov", "state", "region", "area"]),
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


CHINESE_NUMBERS = {
    "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
    "十一": 11, "十二": 12, "十五": 15, "二十": 20, "三十": 30,
}


def parse_nl_limit(question: str, default_limit: int = 30) -> int:
    q = str(question or "")
    numeric = re.search(r"(?:top|前|最高的前|最低的前|排名前)\s*(\d{1,3})", q, re.I)
    if numeric:
        return max(1, min(int(numeric.group(1)), 100))
    chinese = re.search(r"(?:前|最高的前|最低的前|排名前)\s*([一二两三四五六七八九十]{1,3})\s*个?", q)
    if chinese:
        return max(1, min(CHINESE_NUMBERS.get(chinese.group(1), default_limit), 100))
    return default_limit


def wants_ascending_rank(question: str) -> bool:
    return has_any_term(str(question or ""), ["最低", "最少", "倒数", "升序", "从低到高"])


def extract_region_values(question: str) -> list[str]:
    q = str(question or "")
    regions = [
        "华东", "华南", "华北", "华中", "中南", "西南", "西北", "东北", "东南",
        "华东区", "华南区", "华北区", "华中区", "中南区", "西南区", "西北区", "东北区",
        "北京", "上海", "广州", "深圳",
    ]
    found = [region for region in regions if region in q]
    compare_match = re.search(r"对比(?:一下)?(.+?)(?:最近|近|的|销售|表现|$)", q)
    if compare_match:
        for token in re.split(r"和|与|、|,|，|及|以及|vs|VS|/|\\s+", compare_match.group(1)):
            value = re.sub(r"^(一下|下|看看|看)?", "", token).strip()
            value = re.sub(r"(区域|地区|大区|省份|省|城市|市)$", "", value).strip()
            if 2 <= len(value) <= 6 and re.match(r"^[\u4e00-\u9fa5]+$", value):
                found.append(value)
    result: list[str] = []
    seen = set()
    for value in found:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def build_region_value_filter(field: FieldMeta, values: list[str]) -> str:
    if not values:
        return ""
    column = f"`{field.columnName}`"
    clauses = []
    for value in values:
        escaped = value.replace("'", "''")
        clauses.append(f"{column} = '{escaped}'")
        clauses.append(f"{column} LIKE '%{escaped}%'")
    return "(" + " OR ".join(clauses) + ")"


def build_time_filter(question: str, fields: list[FieldMeta]) -> str:
    q = str(question or "")
    time_field = (first_by_type(fields, "DATE") or first_date_like_field(fields))
    if not time_field or not (time_field.fieldType == "DATE" or time_field_match(time_field)):
        return ""
    column = f"`{time_field.columnName}`"
    if "今年" in q:
        return f"YEAR({column}) = YEAR(CURDATE())"
    if "去年" in q:
        return f"YEAR({column}) = YEAR(CURDATE()) - 1"
    if "最近" in q or "近" in q:
        month_match = re.search(r"(?:最近|近)\s*(\d+)\s*个?月", q)
        if month_match:
            return f"{column} >= DATE_SUB(CURDATE(), INTERVAL {int(month_match.group(1))} MONTH)"
        day_match = re.search(r"(?:最近|近)\s*(\d+)\s*天", q)
        if day_match:
            return f"{column} >= DATE_SUB(CURDATE(), INTERVAL {int(day_match.group(1))} DAY)"
        return f"{column} >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)"
    return ""


def build_semantic_filters(question: str, dimension: FieldMeta, fields: list[FieldMeta]) -> list[str]:
    filters = [build_dimension_filter(question, dimension)]
    region_values = extract_region_values(question)
    if region_values:
        region_field = dimension if region_field_match(dimension) else choose_dimension("区域", fields)
        if region_field:
            filters.append(build_region_value_filter(region_field, region_values))
    time_filter = build_time_filter(question, fields)
    if time_filter:
        filters.append(time_filter)
    return [item for item in filters if item]


def resolve_graph_sql_plan(payload: TextToSqlRequest) -> dict[str, Any]:
    hints = payload.graphSqlHints if isinstance(payload.graphSqlHints, dict) else {}
    field_candidates = hints.get("fieldCandidates") if isinstance(hints.get("fieldCandidates"), list) else []
    formula_candidates = hints.get("formulaCandidates") if isinstance(hints.get("formulaCandidates"), list) else []
    ambiguities = hints.get("ambiguities") if isinstance(hints.get("ambiguities"), list) else []
    mapping = hints.get("recommendedMapping") if isinstance(hints.get("recommendedMapping"), dict) else {}

    dim_ref = str(mapping.get("dimensionKey", "")).strip()
    metric_ref = str(mapping.get("metricKey", "")).strip()
    dictionary_dim_ref = str(mapping.get("dictionaryDimensionKey", "")).strip()
    dictionary_metric_ref = str(mapping.get("dictionaryMetricKey", "")).strip()
    metric_formula = normalize_metric_formula(str(mapping.get("metricFormula", "")).strip())

    if not metric_formula and formula_candidates:
        first = formula_candidates[0]
        if isinstance(first, dict):
            metric_formula = normalize_metric_formula(str(first.get("formula", "")).strip())

    field_by_col = {field.columnName.lower(): field for field in payload.fields}
    field_by_display = {field.displayName.lower(): field for field in payload.fields if field.displayName}

    dimension_field = match_field_by_ref(dim_ref, field_by_col, field_by_display)
    metric_field = match_field_by_ref(metric_ref, field_by_col, field_by_display)

    if dimension_field is None and dictionary_dim_ref:
        dimension_field = match_field_by_ref(dictionary_dim_ref, field_by_col, field_by_display)
    if metric_field is None and dictionary_metric_ref:
        metric_field = match_field_by_ref(dictionary_metric_ref, field_by_col, field_by_display)

    if dimension_field is None:
        dimension_field = select_field_from_candidates(field_candidates, payload.fields, prefer_number=False)
    if metric_field is None:
        metric_field = select_field_from_candidates(field_candidates, payload.fields, prefer_number=True)

    resolved: list[dict[str, Any]] = []
    for item in ambiguities:
        if not isinstance(item, dict):
            continue
        resolution = str(item.get("resolution", "")).strip()
        if not resolution:
            continue
        matched = match_field_by_ref(resolution, field_by_col, field_by_display)
        if not matched:
            continue
        resolved.append({"term": item.get("term"), "resolution": resolution, "column": matched.columnName})
        if matched.fieldType == "NUMBER" and metric_field is None:
            metric_field = matched
        if matched.fieldType != "NUMBER" and dimension_field is None:
            dimension_field = matched

    metric_name = ""
    if formula_candidates and isinstance(formula_candidates[0], dict):
        metric_name = str(formula_candidates[0].get("name", "")).strip()

    used = bool(field_candidates or formula_candidates or ambiguities or dim_ref or metric_ref or metric_formula)
    return {
        "used": used,
        "dimension_field": dimension_field,
        "metric_field": metric_field,
        "metric_formula": metric_formula,
        "metric_name": metric_name,
        "decision": {
            "dimensionRef": dim_ref,
            "metricRef": metric_ref,
            "dictionaryDimensionRef": dictionary_dim_ref,
            "dictionaryMetricRef": dictionary_metric_ref,
            "metricFormula": metric_formula,
            "dimensionColumn": dimension_field.columnName if dimension_field else "",
            "metricColumn": metric_field.columnName if metric_field else "",
            "ambiguityResolution": resolved,
        },
    }


def build_graph_guided_sql_result(payload: TextToSqlRequest, graph_plan: dict[str, Any], chart_type: str) -> dict[str, Any]:
    dimension = choose_dimension(payload.question, payload.fields, graph_plan.get("dimension_field"))
    metric = choose_metric(payload.question, payload.fields, graph_plan.get("metric_field"))
    final_chart_type = chart_type if chart_type in {"bar", "line", "pie"} else choose_chart_type(payload.question, dimension)

    if metric and graph_plan.get("metric_formula"):
        formula_expr = build_formula_expression(str(graph_plan.get("metric_formula")), payload.fields)
        if formula_expr:
            value_expr = f"SUM({formula_expr})"
            metric_name = str(graph_plan.get("metric_name") or metric.displayName)
            metric_key = metric.columnName
        else:
            value_expr = f"SUM(CAST(NULLIF(`{metric.columnName}`, '') AS DECIMAL(18,2)))"
            metric_name = metric.displayName
            metric_key = metric.columnName
    elif metric:
        value_expr = f"SUM(CAST(NULLIF(`{metric.columnName}`, '') AS DECIMAL(18,2)))"
        metric_name = metric.displayName
        metric_key = metric.columnName
    else:
        value_expr = "COUNT(1)"
        metric_name = "记录数"
        metric_key = "value"

    dimension_expr = build_dimension_expression(payload.question, dimension)
    limit = parse_nl_limit(payload.question, 30)
    if final_chart_type == "line":
        order_expr = "dim_name ASC"
    else:
        order_expr = "metric_value ASC" if wants_ascending_rank(payload.question) else "metric_value DESC"
    where_expr = " AND ".join(build_semantic_filters(payload.question, dimension, payload.fields))
    sql = (
        f"SELECT {dimension_expr} AS dim_name, {value_expr} AS metric_value "
        f"FROM `{payload.tableName}` "
        f"WHERE {where_expr} "
        f"GROUP BY {dimension_expr} "
        f"ORDER BY {order_expr} LIMIT {limit}"
    )
    return {
        "sql": sql,
        "chartType": final_chart_type,
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
            "图谱候选字段已参与匹配",
            f"推荐图表类型：{final_chart_type}",
        ],
    }


def should_override_sql_with_graph_plan(sql: str, graph_plan: dict[str, Any], payload: TextToSqlRequest) -> bool:
    if not graph_plan.get("used"):
        return False
    sql_lower = (sql or "").lower()
    dimension_field = graph_plan.get("dimension_field")
    metric_field = graph_plan.get("metric_field")
    metric_formula = str(graph_plan.get("metric_formula") or "").strip()

    if dimension_field and f"`{dimension_field.columnName.lower()}`" not in sql_lower:
        return True

    if metric_formula:
        formula_expr = build_formula_expression(metric_formula, payload.fields)
        if formula_expr and formula_expr.lower() not in sql_lower:
            return True
    elif metric_field and f"`{metric_field.columnName.lower()}`" not in sql_lower:
        return True

    return False


def should_override_sql_with_semantic_plan(sql: str, ai_result: dict[str, Any], payload: TextToSqlRequest) -> bool:
    sql_lower = str(sql or "").lower()
    question = payload.question or ""
    if not sql_lower:
        return True

    expected_dimension = choose_dimension(question, payload.fields)
    expected_metric = choose_metric(question, payload.fields)
    mapping = ai_result.get("fieldMapping") if isinstance(ai_result.get("fieldMapping"), dict) else {}
    mapped_dimension = str(mapping.get("dimensionKey") or mapping.get("dimension") or "").strip().lower()
    mapped_metric = str(mapping.get("metricKey") or mapping.get("metric") or "").strip().lower()

    if expected_dimension and has_macro_region_value(question) and expected_dimension.columnName.lower() not in sql_lower:
        return True
    if expected_dimension and expected_dimension.columnName.lower() not in sql_lower and expected_dimension.columnName.lower() != mapped_dimension:
        return True
    if expected_metric and expected_metric.columnName.lower() not in sql_lower and expected_metric.columnName.lower() != mapped_metric:
        return True
    if parse_nl_limit(question, 30) != 30 and f"limit {parse_nl_limit(question, 30)}" not in sql_lower:
        return True
    for value in extract_region_values(question):
        if value.lower() not in sql_lower and value not in sql:
            return True
    if ("今年" in question and "year(" not in sql_lower) or ("最近" in question and "date_sub" not in sql_lower and "interval" not in sql_lower):
        return True
    if wants_ascending_rank(question) and "metric_value asc" not in sql_lower and " asc" not in sql_lower:
        return True
    if not wants_ascending_rank(question) and any(token in question for token in ["最高", "最多", "排名", "排行", "top", "前"]) and " desc" not in sql_lower:
        return True
    return False


def build_graph_hint_prompt(payload: TextToSqlRequest) -> str:
    plan = resolve_graph_sql_plan(payload)
    hints = payload.graphSqlHints if isinstance(payload.graphSqlHints, dict) else {}
    top_fields = hints.get("fieldCandidates") if isinstance(hints.get("fieldCandidates"), list) else []
    top_formulas = hints.get("formulaCandidates") if isinstance(hints.get("formulaCandidates"), list) else []
    ambiguities = hints.get("ambiguities") if isinstance(hints.get("ambiguities"), list) else []

    if not plan.get("used"):
        return "无图谱提示。"

    dim = plan.get("dimension_field")
    metric = plan.get("metric_field")
    lines = [
        f"- 推荐维度字段: {(dim.columnName if dim else '') or '未命中'}",
        f"- 推荐指标字段: {(metric.columnName if metric else '') or '未命中'}",
        f"- 推荐业务公式: {plan.get('metric_formula') or '无'}",
    ]
    if top_fields:
        labels = []
        for item in top_fields[:5]:
            if isinstance(item, dict):
                labels.append(str(item.get("sourceId") or item.get("label") or item.get("nodeKey") or ""))
        if labels:
            lines.append("- 字段候选: " + ", ".join([x for x in labels if x]))
    if top_formulas:
        formulas = []
        for item in top_formulas[:3]:
            if isinstance(item, dict):
                name = str(item.get("name") or "")
                formula = str(item.get("formula") or "")
                if name and formula:
                    formulas.append(f"{name}={formula}")
                elif formula:
                    formulas.append(formula)
        if formulas:
            lines.append("- 公式候选: " + "; ".join(formulas))
    if ambiguities:
        resolved = []
        for item in ambiguities[:3]:
            if isinstance(item, dict):
                term = str(item.get("term") or "")
                resolution = str(item.get("resolution") or "")
                if term and resolution:
                    resolved.append(f"{term}->{resolution}")
        if resolved:
            lines.append("- 歧义消解: " + ", ".join(resolved))
    return "\n".join(lines)


def select_field_from_candidates(candidates: list[Any], fields: list[FieldMeta], prefer_number: bool) -> FieldMeta | None:
    if not candidates:
        return None
    field_by_col = {field.columnName.lower(): field for field in fields}
    field_by_display = {field.displayName.lower(): field for field in fields if field.displayName}
    items = [item for item in candidates if isinstance(item, dict)]
    items.sort(
        key=lambda item: (
            1 if as_bool(item.get("dictionaryMatched")) else 0,
            read_float(item.get("dictionaryBoost")),
            read_float(item.get("score")),
        ),
        reverse=True,
    )
    for item in items:
        if not isinstance(item, dict):
            continue
        refs = [
            str(item.get("sourceId", "")).strip(),
            str(item.get("label", "")).strip(),
            str(item.get("nodeKey", "")).strip(),
        ]
        for ref in refs:
            matched = match_field_by_ref(ref, field_by_col, field_by_display)
            if not matched:
                continue
            if prefer_number and matched.fieldType != "NUMBER":
                continue
            if not prefer_number and matched.fieldType == "NUMBER":
                continue
            return matched
    return None


def as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def read_float(value: Any) -> float:
    try:
        return float(str(value))
    except Exception:
        return 0.0


def match_field_by_ref(ref: str, field_by_col: dict[str, FieldMeta], field_by_display: dict[str, FieldMeta]) -> FieldMeta | None:
    if not ref:
        return None
    for token in tokenize_ref(ref):
        matched = field_by_col.get(token.lower()) or field_by_display.get(token.lower())
        if matched:
            return matched
    return None


def tokenize_ref(ref: str) -> list[str]:
    raw = re.split(r"[^a-zA-Z0-9_\u4e00-\u9fa5]+", ref)
    tokens = [part.strip() for part in raw if part and part.strip()]
    seen = set()
    out = []
    for token in tokens:
        lower = token.lower()
        if lower in seen:
            continue
        seen.add(lower)
        out.append(token)
    return out


def build_formula_expression(formula: str, fields: list[FieldMeta]) -> str:
    expr = str(formula or "").strip()
    if not expr:
        return ""
    lower = expr.lower()
    banned = [";", "--", "/*", "*/", "drop", "delete", "insert", "update", "alter", "truncate"]
    if any(token in lower for token in banned):
        return ""
    field_by_col = {field.columnName.lower(): field for field in fields}
    field_by_display = {field.displayName.lower(): field for field in fields if field.displayName}
    tokens = sorted(set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", expr)), key=len, reverse=True)
    reserved = {"sum", "avg", "count", "max", "min", "cast", "nullif", "if", "case", "when", "then", "else", "end"}
    rewritten = expr
    for token in tokens:
        key = token.lower()
        if key in reserved:
            continue
        matched = field_by_col.get(key) or field_by_display.get(key)
        if not matched:
            continue
        rewritten = re.sub(rf"(?<![A-Za-z0-9_]){re.escape(token)}(?![A-Za-z0-9_])", f"`{matched.columnName}`", rewritten)
    return rewritten


def normalize_metric_formula(formula: str) -> str:
    expr = str(formula or "").strip()
    if not expr:
        return ""
    if _is_wrapped_aggregate(expr):
        inner = _strip_outer_aggregate(expr)
        return inner.strip() if inner else expr
    return expr


def _is_wrapped_aggregate(expr: str) -> bool:
    return bool(re.match(r"(?is)^\s*(sum|avg|max|min|count)\s*\(.*\)\s*$", expr))


def _strip_outer_aggregate(expr: str) -> str:
    text = expr.strip()
    m = re.match(r"(?is)^\s*(sum|avg|max|min|count)\s*\(", text)
    if not m:
        return text
    open_idx = m.end() - 1
    depth = 0
    for idx in range(open_idx, len(text)):
        ch = text[idx]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:idx]
    return text


def normalize_ai_sql_result(parsed: dict[str, Any], payload: TextToSqlRequest) -> dict[str, Any]:
    field_mapping = parsed.get("fieldMapping") if isinstance(parsed.get("fieldMapping"), dict) else {}
    sql = str(parsed.get("sql", ""))
    chart_type = str(parsed.get("chartType", "bar"))
    reasoning = parsed.get("reasoning") if isinstance(parsed.get("reasoning"), list) else []

    dimension_key = str(field_mapping.get("dimensionKey") or field_mapping.get("dimension") or "")
    metric_key = str(field_mapping.get("metricKey") or field_mapping.get("metric") or "")

    graph_plan = resolve_graph_sql_plan(payload)
    field_by_column = {field.columnName: field for field in payload.fields}
    dimension_field = (
        field_by_column.get(dimension_key)
        or graph_plan["dimension_field"]
        or choose_dimension(payload.question, payload.fields)
    )
    metric_field = (
        field_by_column.get(metric_key)
        or graph_plan["metric_field"]
        or choose_metric(payload.question, payload.fields)
    )

    if dimension_field:
        dimension_key = dimension_field.columnName
    if metric_field:
        metric_key = metric_field.columnName

    if not dimension_key:
        dimension_key = choose_dimension(payload.question, payload.fields, graph_plan["dimension_field"]).columnName
    if not metric_key:
        fallback_metric = choose_metric(payload.question, payload.fields, graph_plan["metric_field"])
        metric_key = fallback_metric.columnName if fallback_metric else "value"

    if " AS dim_name" not in sql and dimension_key:
        sql = rewrite_sql_alias(sql, dimension_key, metric_key)

    if should_override_sql_with_graph_plan(sql, graph_plan, payload):
        guided = build_graph_guided_sql_result(payload, graph_plan, chart_type)
        sql = guided["sql"]
        field_mapping = guided["fieldMapping"]
        chart_type = guided["chartType"]
        if isinstance(reasoning, list):
            reasoning.append("图谱提示与模型输出存在偏差，已按图谱映射自动纠偏")

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
        "graphSqlHintsUsed": graph_plan["used"],
        "graphDecision": graph_plan["decision"],
    }


def has_dictionary_intent(question: str) -> bool:
    q = (question or "").strip()
    return any(token in q for token in ["业务字典", "字典", "词典", "同义词", "术语", "黑话", "映射"])


def has_formula_intent(question: str) -> bool:
    q = (question or "").strip()
    return any(token in q for token in ["业务公式", "指标公式", "公式", "衍生指标", "利润率", "毛利率", "转化率", "同比", "环比", "核心指标包含"]) \
        or bool(re.search(r".+(?:按|按照).+(?:除以|乘以|加上|减去|/|\*|\+|-).+(?:算|计算)?", q))


def split_top_level_segments(text: str, separators: list[str] | None = None) -> list[str]:
    separators = separators or [";", "；", "\n", "，", ",", "、"]
    result: list[str] = []
    buffer = ""
    stack: list[str] = []
    open_chars = {"(", "（", "[", "{"}
    close_to_open = {")": "(", "）": "（", "]": "[", "}": "{"}
    for ch in str(text or ""):
        if ch in open_chars:
            stack.append(ch)
            buffer += ch
            continue
        if ch in close_to_open:
            if stack and stack[-1] == close_to_open[ch]:
                stack.pop()
            buffer += ch
            continue
        if ch in separators and not stack:
            value = buffer.strip()
            if value:
                result.append(value)
            buffer = ""
            continue
        buffer += ch
    tail = buffer.strip()
    if tail:
        result.append(tail)
    return result


def normalize_ref_token(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_\u4e00-\u9fa5]+", "", str(value or "").strip().lower())


def resolve_field_from_ref(ref: str, fields: list[FieldMeta]) -> FieldMeta | None:
    normalized_ref = normalize_ref_token(ref)
    if not normalized_ref:
        return None
    exact_match: FieldMeta | None = None
    best_match: tuple[int, FieldMeta] | None = None
    for field in fields:
        aliases = [
            field.columnName or "",
            field.displayName or "",
            field.sourceFieldName or "",
            field.fieldComment or "",
        ]
        if field.synonyms:
            aliases.extend(re.split(r"[,，;；、\s]+", field.synonyms))
        normalized_aliases = [normalize_ref_token(alias) for alias in aliases if alias]
        if normalized_ref in normalized_aliases:
            exact_match = field
            break
        for alias in normalized_aliases:
            if not alias:
                continue
            if normalized_ref in alias or alias in normalized_ref:
                score = min(len(normalized_ref), len(alias))
                if best_match is None or score > best_match[0]:
                    best_match = (score, field)
    return exact_match or (best_match[1] if best_match else None)


def normalize_dictionary_entries(entries: Any, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    if not isinstance(entries, list):
        return result
    seen: set[str] = set()
    for item in entries:
        if not isinstance(item, dict):
            continue
        term = str(item.get("term") or "").strip()
        field_ref = str(item.get("field") or "").strip()
        synonyms = str(item.get("synonyms") or "").strip()
        matched = resolve_field_from_ref(field_ref or term, fields)
        field_name = matched.columnName if matched else field_ref
        if not term or not field_name:
            continue
        key = f"{term.lower()}@@{field_name.lower()}"
        if key in seen:
            continue
        seen.add(key)
        result.append({
            "term": term,
            "field": field_name,
            "synonyms": synonyms,
        })
    return result


def normalize_patch_operations(entries: Any, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    if not isinstance(entries, list):
        return []
    result: list[dict[str, Any]] = []
    for item in entries:
        if not isinstance(item, dict):
            continue
        target_type = str(item.get("targetType") or "").strip()
        action = str(item.get("action") or "UPSERT").strip().upper()
        if target_type not in {"dictionaryEntry", "metricDefinition", "fieldBinding"}:
            continue
        if action not in {"UPSERT", "DELETE"}:
            action = "UPSERT"
        semantic_action = normalize_model_semantic_action(item.get("semanticAction"), target_type, item.get("bindingType"))
        if target_type == "fieldBinding":
            name = str(item.get("name") or item.get("term") or item.get("label") or "").strip()
            field_ref = str(item.get("field") or "").strip()
            binding_type = normalize_binding_type(item.get("bindingType"))
            matched = resolve_field_from_ref(field_ref or name, fields)
            field_name = matched.columnName if matched else field_ref
            if not name or not field_name:
                continue
            result.append({
                "semanticAction": semantic_action,
                "targetType": "fieldBinding",
                "action": action,
                "bindingType": binding_type,
                "name": name,
                "field": field_name,
            })
            continue
        if target_type == "dictionaryEntry":
            term = str(item.get("term") or "").strip()
            field_ref = str(item.get("field") or "").strip()
            synonyms = str(item.get("synonyms") or "").strip()
            matched = resolve_field_from_ref(field_ref or term or synonyms, fields)
            field_name = matched.columnName if matched else field_ref
            if not term:
                continue
            result.append({
                "semanticAction": semantic_action,
                "targetType": "dictionaryEntry",
                "action": action,
                "term": term,
                "field": field_name,
                "synonyms": synonyms,
            })
            continue

        name = str(item.get("name") or "").strip()
        formula = normalize_metric_formula(str(item.get("formula") or "").strip())
        aggregation = str(item.get("aggregation") or "SUM").strip().upper() or "SUM"
        field_ref = str(item.get("field") or "").strip()
        matched = resolve_field_from_ref(field_ref or formula or name, fields)
        field_name = matched.columnName if matched else field_ref
        if not name:
            continue
        result.append({
            "semanticAction": semantic_action,
            "targetType": "metricDefinition",
            "action": action,
            "name": name,
            "field": field_name,
            "aggregation": aggregation if aggregation in {"SUM", "COUNT", "AVG", "MAX", "MIN"} else "SUM",
            "formula": rewrite_formula_to_column_names(formula, fields),
        })
    return deduplicate_patch_operations(result)


def normalize_model_semantic_action(value: Any, target_type: str, binding_type: Any = "") -> str:
    raw = str(value or "").strip().upper().replace("-", "_")
    allowed = {
        "FIELD_BINDING",
        "METRIC_FORMULA_UPDATE",
        "METRIC_SCOPE_UPDATE",
        "DICTIONARY_UPSERT",
        "DIMENSION_BINDING",
        "MODEL_CREATE",
        "MODEL_APPLY",
        "MODEL_PUBLISH",
    }
    if raw in allowed:
        return raw
    normalized_target = str(target_type or "").strip()
    normalized_binding = str(binding_type or "").strip()
    if normalized_target == "metricDefinition":
        return "METRIC_FORMULA_UPDATE"
    if normalized_target == "dictionaryEntry":
        return "DICTIONARY_UPSERT"
    if normalized_target == "dimensionDefinition" or normalized_binding == "dimensionDefinition":
        return "DIMENSION_BINDING"
    return "FIELD_BINDING"


def normalize_metric_definitions(entries: Any, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    if not isinstance(entries, list):
        return result
    seen: set[str] = set()
    for item in entries:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        formula = normalize_metric_formula(str(item.get("formula") or "").strip())
        aggregation = str(item.get("aggregation") or "SUM").strip().upper() or "SUM"
        field_ref = str(item.get("field") or "").strip()
        matched = resolve_field_from_ref(field_ref or formula or name, fields)
        field_name = matched.columnName if matched else field_ref
        if not name:
            continue
        key = name.lower()
        if key in seen:
            continue
        seen.add(key)
        result.append({
            "name": name,
            "field": field_name,
            "aggregation": aggregation if aggregation in {"SUM", "COUNT", "AVG", "MAX", "MIN"} else "SUM",
            "formula": rewrite_formula_to_column_names(formula, fields),
        })
    return result


def rewrite_formula_to_column_names(formula: str, fields: list[FieldMeta]) -> str:
    expr = str(formula or "").strip()
    if not expr:
        return ""
    rewritten = expr
    tokens = sorted(set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*|[\u4e00-\u9fa5]{2,}", expr)), key=len, reverse=True)
    for token in tokens:
        matched = resolve_field_from_ref(token, fields)
        if not matched:
            continue
        rewritten = re.sub(rf"(?<![A-Za-z0-9_\u4e00-\u9fa5]){re.escape(token)}(?![A-Za-z0-9_\u4e00-\u9fa5])", matched.columnName, rewritten)
    return rewritten


def deduplicate_patch_operations(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in entries:
        target_type = str(item.get("targetType") or "").strip()
        key_name = str(item.get("name") or item.get("term") or "").strip().lower()
        action = str(item.get("action") or "UPSERT").strip().upper()
        binding_type = str(item.get("bindingType") or "").strip()
        field_name = str(item.get("field") or "").strip().lower()
        key = f"{target_type}@@{binding_type}@@{key_name}@@{field_name}@@{action}"
        if not key_name or key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def build_patch_operations_from_question(
    question: str,
    fields: list[FieldMeta],
    existing_dictionary_entries: list[dict[str, Any]] | None,
    existing_metric_definitions: list[dict[str, Any]] | None,
    existing_dimensions: list[dict[str, Any]] | None,
) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    operations.extend(build_metric_patch_operations(question, fields, existing_metric_definitions or []))
    implicit_metric = build_implicit_metric_formula_from_question(question, fields)
    if implicit_metric:
        operations.append({
            "semanticAction": "METRIC_FORMULA_UPDATE",
            "targetType": "metricDefinition",
            "action": "UPSERT",
            "name": implicit_metric["name"],
            "field": implicit_metric.get("field", ""),
            "aggregation": implicit_metric.get("aggregation", "AVG"),
            "formula": implicit_metric.get("formula", ""),
        })
    if not has_metric_scope_or_formula_intent(question):
        operations.extend(build_field_binding_patch_operations(
            question,
            fields,
            existing_dictionary_entries or [],
            existing_metric_definitions or [],
            existing_dimensions or [],
        ))
        operations.extend(build_dictionary_patch_operations(question, fields))
    if any(token in question for token in ["删除", "移除", "去掉", "取消"]) and ((existing_dictionary_entries or []) or (existing_metric_definitions or [])):
        operations.extend(build_delete_patch_operations(question, existing_dictionary_entries, existing_metric_definitions or []))
    return deduplicate_patch_operations(operations)


def build_field_binding_patch_operations(
    question: str,
    fields: list[FieldMeta],
    existing_dictionary_entries: list[dict[str, Any]],
    existing_metric_definitions: list[dict[str, Any]],
    existing_dimensions: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not has_binding_intent(question):
        return []
    operations: list[dict[str, Any]] = []
    overall_binding_type = infer_binding_type_from_text(question)
    patterns = [
        re.compile(r"(?:把|将)?(.+?)(?:的)?(?:目标)?(?:绑定字段|字段绑定|字段)?(?:改成|改为|修改为|更新为|绑定到|绑定为|绑定至|映射到|映射为|映射至|对应到|对应为|对应至|改绑到|关联到|关联为)\s*([^,，;；。\n]+)$"),
        re.compile(r"(?:把|将)?(.+?)\s*(?:->|=>|→)\s*([^,，;；。\n]+)$"),
    ]
    for item in split_top_level_segments(question):
        cleaned_item = str(item or "").strip().strip("。")
        if not cleaned_item:
            continue
        for segment in split_top_level_segments(cleaned_item, separators=["；", ";", "\n", "，", ",", "、"]):
            cleaned = str(segment or "").strip().strip("。")
            if not cleaned:
                continue
            matched_pair = None
            for pattern in patterns:
                matched_pair = pattern.search(cleaned)
                if matched_pair:
                    break
            if not matched_pair:
                continue
            subject = cleanup_binding_subject(str(matched_pair.group(1) or "").strip())
            field_ref = str(matched_pair.group(2) or "").strip().strip("：:，,；; ")
            matched_field = resolve_field_from_ref(field_ref, fields)
            if not subject or not matched_field:
                continue
            binding_type = infer_binding_type_from_text(cleaned)
            if binding_type == "AUTO":
                binding_type = infer_binding_type_from_existing_name(
                    subject,
                    existing_dictionary_entries,
                    existing_metric_definitions,
                    existing_dimensions,
                    overall_binding_type,
                )
            operations.append({
                "semanticAction": "DIMENSION_BINDING" if binding_type == "dimensionDefinition" else "FIELD_BINDING",
                "targetType": "fieldBinding",
                "action": "UPSERT",
                "bindingType": binding_type,
                "name": subject,
                "field": matched_field.columnName,
            })
    return deduplicate_patch_operations(operations)


def build_dictionary_patch_operations(question: str, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    pattern = re.compile(r"(.+?)(?:映射到|映射为|映射至|映射|对应到|对应为|对应至|对应|绑定到|绑定为|绑定至|绑定|关联到|关联为|关联)\s*([A-Za-z_][A-Za-z0-9_]*)")
    for item in split_top_level_segments(question):
        cleaned = strip_model_operation_prefix(item, ["业务字典", "字典", "词典", "同义词", "术语"])
        for segment in split_top_level_segments(cleaned, separators=["；", ";", "\n", "，", ",", "、"]):
            matched = pattern.search(segment)
            if not matched:
                continue
            term = cleanup_business_item_name(str(matched.group(1) or "").strip())
            field_ref = str(matched.group(2) or "").strip()
            field = resolve_field_from_ref(field_ref or term, fields)
            if not term:
                continue
            operations.append({
                "targetType": "dictionaryEntry",
                "action": "UPSERT",
                "term": term,
                "field": field.columnName if field else field_ref,
                "synonyms": "",
            })
    return operations


def build_metric_patch_operations(question: str, fields: list[FieldMeta], existing_metric_definitions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    segments = split_top_level_segments(question, separators=["；", ";", "\n"])
    for item in segments:
        cleaned = strip_model_operation_prefix(item, ["指标公式", "业务公式", "公式"])
        parts = [cleaned] if looks_like_single_formula_assignment(cleaned) else split_top_level_segments(cleaned, separators=["，", ",", "、"])
        if not parts:
            parts = [cleaned]
        for part in parts:
            metric_op = parse_metric_operation_segment(part, fields, existing_metric_definitions)
            if metric_op:
                operations.append(metric_op)
    return operations


def has_metric_scope_or_formula_intent(question: str) -> bool:
    q = str(question or "").strip()
    if not q:
        return False
    return bool(re.search(r"口径|以后|后续|之后|统一用|统一按|就按|按.*算|按.*统计|按照|除以|乘以|加上|减去|公式", q)
                or looks_like_single_formula_assignment(q))


def looks_like_single_formula_assignment(text: str) -> bool:
    value = str(text or "").strip()
    if not value:
        return False
    return bool(re.search(r"[\u4e00-\u9fa5A-Za-z0-9_]{1,30}\s*(=|＝|:|：)\s*.+[A-Za-z_][A-Za-z0-9_]*", value)
                and re.search(r"[+\-*/]|除以|乘以|加上|减去", value))


def parse_metric_operation_segment(
    segment: str,
    fields: list[FieldMeta],
    existing_metric_definitions: list[dict[str, Any]],
) -> dict[str, Any] | None:
    text = str(segment or "").strip()
    if not text:
        return None

    scope_match = re.search(r"(?:以后|后续|之后)?(?:报表里(?:的)?|模型里(?:的)?|指标)?\s*([\u4e00-\u9fa5A-Za-z0-9_]{2,20}?)(?:统一用|统一按|就按|按|按照|口径改成|口径改为|改成|改为|算作|当作)\s*([^，。；;\n]+)", text)
    if scope_match:
        name = match_existing_metric_name(str(scope_match.group(1) or "").strip(), existing_metric_definitions)
        formula = str(scope_match.group(2) or "").strip()
        return build_metric_patch_operation(name, formula, fields, "METRIC_SCOPE_UPDATE")

    formula_match = re.search(r"([\u4e00-\u9fa5A-Za-z0-9_]{2,20}?)(?:按|按照)\s*(.+?(?:除以|乘以|加上|减去|/|\*|\+|-).+?)(?:算|计算|统计)?$", text)
    if formula_match:
        name = match_existing_metric_name(str(formula_match.group(1) or "").strip(), existing_metric_definitions)
        formula = str(formula_match.group(2) or "").strip()
        return build_metric_patch_operation(name, formula, fields, "METRIC_FORMULA_UPDATE")

    direct_match = re.match(r"(.+?)\s*[=:：＝]\s*(.+)$", text)
    if direct_match:
        name = str(direct_match.group(1) or "").strip()
        formula = str(direct_match.group(2) or "").strip()
        return build_metric_patch_operation(name, formula, fields, "METRIC_FORMULA_UPDATE")

    modify_match = re.search(r"(?:把|将)?(.+?)(?:指标|公式)?(?:改成|修改为|更新为|调整为|设为)\s*(.+)$", text)
    if modify_match:
        name = match_existing_metric_name(str(modify_match.group(1) or "").strip(), existing_metric_definitions)
        formula = str(modify_match.group(2) or "").strip()
        return build_metric_patch_operation(name, formula, fields, "METRIC_FORMULA_UPDATE")

    add_match = re.search(r"(?:新增|增加|添加|补充)(?:一个|一条)?(.+?)(?:指标|公式)[，,:： ]*(?:公式是|为)?\s*(.+)$", text)
    if add_match:
        name = str(add_match.group(1) or "").strip()
        formula = str(add_match.group(2) or "").strip()
        return build_metric_patch_operation(name, formula, fields, "METRIC_FORMULA_UPDATE")

    return None


def build_metric_patch_operation(name: str, formula: str, fields: list[FieldMeta], semantic_action: str = "METRIC_FORMULA_UPDATE") -> dict[str, Any] | None:
    metric_name = cleanup_business_item_name(name)
    metric_formula = normalize_formula_phrase(str(formula or "").strip())
    if not metric_name or not metric_formula:
        return None
    rewritten_formula = rewrite_formula_to_column_names(metric_formula, fields)
    primary = ""
    for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", rewritten_formula):
        matched = resolve_field_from_ref(token, fields)
        if matched:
            primary = matched.columnName
            break
    return {
        "semanticAction": semantic_action,
        "targetType": "metricDefinition",
        "action": "UPSERT",
        "name": metric_name,
        "field": primary,
        "aggregation": infer_metric_aggregation(metric_name, rewritten_formula),
        "formula": rewritten_formula,
    }


def normalize_formula_phrase(formula: str) -> str:
    value = str(formula or "").strip()
    if not value:
        return ""
    replacements = [
        ("除以", " / "),
        ("乘以", " * "),
        ("加上", " + "),
        ("减去", " - "),
        ("加", " + "),
        ("减", " - "),
        ("乘", " * "),
        ("除", " / "),
    ]
    for old, new in replacements:
        value = value.replace(old, new)
    return re.sub(r"\s+", " ", value).strip("。；;，, ")


def infer_metric_aggregation(name: str, formula: str) -> str:
    text = f"{name} {formula}"
    if any(token in text for token in ["率", "均", "平均", "占比", "比例"]):
        return "AVG"
    if any(token in text for token in ["数", "量", "次数", "人数", "笔数"]):
        return "COUNT" if "/" not in formula and "+" not in formula and "-" not in formula and "*" not in formula else "SUM"
    return "SUM"


def match_existing_metric_name(name: str, existing_metric_definitions: list[dict[str, Any]]) -> str:
    metric_name = str(name or "").strip()
    if not metric_name:
        return ""
    normalized = normalize_ref_token(metric_name)
    for item in existing_metric_definitions:
        existing_name = str(item.get("name") or "").strip()
        if normalize_ref_token(existing_name) == normalized:
            return existing_name
    for item in existing_metric_definitions:
        existing_name = str(item.get("name") or "").strip()
        existing_normalized = normalize_ref_token(existing_name)
        if normalized and (normalized in existing_normalized or existing_normalized in normalized):
            return existing_name
    return metric_name


def infer_binding_type_from_text(text: str) -> str:
    value = str(text or "").strip()
    if any(token in value for token in ["维度"]):
        return "dimensionDefinition"
    if any(token in value for token in ["公式", "指标"]):
        return "metricDefinition"
    if any(token in value for token in ["字典", "词典", "同义词", "术语", "黑话"]):
        return "dictionaryEntry"
    return "AUTO"


def infer_binding_type_from_existing_name(
    name: str,
    existing_dictionary_entries: list[dict[str, Any]],
    existing_metric_definitions: list[dict[str, Any]],
    existing_dimensions: list[dict[str, Any]],
    fallback: str = "AUTO",
) -> str:
    normalized_name = normalize_ref_token(name)
    if not normalized_name:
        return fallback or "AUTO"
    dictionary_hit = any(binding_name_matches(name, str(item.get("term") or ""), str(item.get("synonyms") or "")) for item in existing_dictionary_entries)
    metric_hit = any(binding_name_matches(name, str(item.get("name") or "")) for item in existing_metric_definitions)
    dimension_hit = any(binding_name_matches(name, str(item.get("name") or "")) for item in existing_dimensions)
    hit_types = [kind for kind, hit in [
        ("dictionaryEntry", dictionary_hit),
        ("metricDefinition", metric_hit),
        ("dimensionDefinition", dimension_hit),
    ] if hit]
    if len(hit_types) == 1:
        return hit_types[0]
    return fallback or "AUTO"


def binding_name_matches(name: str, primary_name: str, synonyms: str = "") -> bool:
    normalized_name = normalize_ref_token(name)
    if not normalized_name:
        return False
    candidates = [primary_name]
    if synonyms:
        candidates.extend(re.split(r"[,，;；、\s]+", synonyms))
    for candidate in candidates:
        normalized_candidate = normalize_ref_token(candidate)
        if not normalized_candidate:
            continue
        if normalized_name == normalized_candidate or normalized_name in normalized_candidate or normalized_candidate in normalized_name:
            return True
    return False


def cleanup_binding_subject(text: str) -> str:
    value = str(text or "").strip()
    value = re.sub(r"^(请|请你|帮我|麻烦|把|将)+", "", value).strip()
    value = re.sub(r"^(?:当前|这个|该)?(?:业务)?模型(?:里|中的|内的)?", "", value).strip()
    value = re.sub(r"^(?:业务字典|字典|词典|同义词|术语|业务公式|指标公式|公式|指标|维度)(?:里|中的|内的)?", "", value).strip()
    value = re.sub(r"(?:业务字典|字典|词典|同义词|术语|业务公式|指标公式|公式|指标|维度|字段)$", "", value).strip()
    return value.strip("：:，,；; ")


def strip_model_operation_prefix(text: str, nouns: list[str]) -> str:
    value = str(text or "").strip()
    if not value:
        return ""
    noun_group = "|".join(re.escape(noun) for noun in nouns)
    value = re.sub(r"^(请|请你|帮我|麻烦|给)?", "", value).strip()
    value = re.sub(r"^(?:这个|该|当前)?(?:业务)?模型(?:里|中的|内的)?", "", value).strip()
    value = re.sub(rf"^(?:新增|增加|添加|创建|补充|修改|更新)?(?:{noun_group})\s*[:：]?", "", value).strip()
    value = re.sub(rf"^(?:新增|增加|添加|创建|补充|修改|更新)(?:一个|一条)?(?:{noun_group})\s*[:：]?", "", value).strip()
    return value


def cleanup_business_item_name(text: str) -> str:
    value = str(text or "").strip().strip("：:，,；; ")
    if not value:
        return ""
    value = re.sub(r"^(请|请你|帮我|麻烦|给|把|将)+", "", value).strip()
    value = re.sub(r"^(?:这个|该|当前)?(?:业务)?模型(?:里|中的|内的)?", "", value).strip()
    value = re.sub(r"^(?:新增|增加|添加|创建|补充|修改|更新)(?:一个|一条)?", "", value).strip()
    value = re.sub(r"^(?:业务字典|字典|词典|同义词|术语|业务公式|指标公式|公式|指标|维度)(?:里|中的|内的)?", "", value).strip()
    value = re.sub(r"(?:业务字典|字典|词典|同义词|术语|业务公式|指标公式|公式|指标|维度|字段)$", "", value).strip()
    return value.strip("：:，,；; ")


def normalize_binding_type(value: Any) -> str:
    raw = str(value or "").strip()
    mapping = {
        "dictionary": "dictionaryEntry",
        "dictionaryentry": "dictionaryEntry",
        "metric": "metricDefinition",
        "metricdefinition": "metricDefinition",
        "dimension": "dimensionDefinition",
        "dimensiondefinition": "dimensionDefinition",
        "auto": "AUTO",
    }
    normalized = mapping.get(normalize_ref_token(raw), raw)
    if normalized not in {"dictionaryEntry", "metricDefinition", "dimensionDefinition", "AUTO"}:
        return "AUTO"
    return normalized


def build_delete_patch_operations(
    question: str,
    existing_dictionary_entries: list[dict[str, Any]],
    existing_metric_definitions: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    normalized_question = normalize_ref_token(question)
    for item in existing_dictionary_entries:
        term = str(item.get("term") or "").strip()
        if term and normalize_ref_token(term) and normalize_ref_token(term) in normalized_question:
            operations.append({
                "targetType": "dictionaryEntry",
                "action": "DELETE",
                "term": term,
                "field": str(item.get("field") or "").strip(),
                "synonyms": str(item.get("synonyms") or "").strip(),
            })
    for item in existing_metric_definitions:
        name = str(item.get("name") or "").strip()
        if name and normalize_ref_token(name) and normalize_ref_token(name) in normalized_question:
            operations.append({
                "targetType": "metricDefinition",
                "action": "DELETE",
                "name": name,
                "field": str(item.get("field") or "").strip(),
                "aggregation": str(item.get("aggregation") or "SUM").strip().upper() or "SUM",
                "formula": str(item.get("formula") or "").strip(),
            })
    return operations


def build_dictionary_entries_from_question(question: str, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    pattern = re.compile(r"(.+?)(?:映射|对应|绑定|关联到|关联)\s*([A-Za-z_][A-Za-z0-9_]*)")
    for item in split_top_level_segments(question):
        cleaned = re.sub(r"^(新增|增加|添加|创建)?(业务字典|字典|词典|同义词)\s*[:：]?", "", item).strip()
        match = pattern.search(cleaned)
        if not match:
            continue
        term = str(match.group(1) or "").strip().strip("：:，,；; ")
        field_ref = str(match.group(2) or "").strip()
        matched = resolve_field_from_ref(field_ref, fields)
        if term and matched:
            entries.append({"term": term, "field": matched.columnName, "synonyms": ""})
    return normalize_dictionary_entries(entries, fields)


def build_metric_entries_from_question(question: str, fields: list[FieldMeta]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for item in split_top_level_segments(question):
        cleaned = re.sub(r"^(新增|增加|添加)?(指标公式|业务公式|公式|核心指标包含)\s*[:：]?", "", item).strip()
        segments = split_top_level_segments(cleaned, separators=[";", "；", "\n", "，", ",", "、"])
        if not segments:
            segments = [cleaned]
        for segment in segments:
            match = re.match(r"(.+?)\s*[=:：]\s*(.+)$", segment.strip())
            if not match:
                continue
            name = str(match.group(1) or "").strip()
            formula = str(match.group(2) or "").strip()
            if not name or not formula:
                continue
            normalized_formula = rewrite_formula_to_column_names(formula, fields)
            primary = ""
            for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", normalized_formula):
                matched = resolve_field_from_ref(token, fields)
                if matched:
                    primary = matched.columnName
                    break
            entries.append({
                "name": name,
                "field": primary,
                "aggregation": "AVG" if any(token in name for token in ["率", "均", "平均"]) else "SUM",
                "formula": normalized_formula,
            })
    implicit = build_implicit_metric_formula_from_question(question, fields)
    if implicit:
        entries.append(implicit)
    return normalize_metric_definitions(entries, fields)


def build_implicit_metric_formula_from_question(question: str, fields: list[FieldMeta]) -> dict[str, Any] | None:
    text = str(question or "").strip()
    match = re.search(r"(.+?)(?:就)?(?:按|按照)\s*(.+?)(?:来算|计算|算)?$", text)
    if not match:
        return None
    name = cleanup_business_item_name(match.group(1))
    formula = normalize_formula_phrase(match.group(2))
    if not name or not formula:
        return None
    rewritten = rewrite_formula_to_column_names(formula, fields)
    primary = ""
    for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", rewritten):
        matched = resolve_field_from_ref(token, fields)
        if matched:
            primary = matched.columnName
            break
    return {
        "name": name,
        "field": primary,
        "aggregation": "AVG" if any(token in name for token in ["率", "均", "平均"]) else "SUM",
        "formula": rewritten,
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


def normalize_tts_gender(value: Any) -> str:
    text = str(value or "").strip().lower()
    if text in {"male", "man", "m", "男", "男声", "男性"}:
        return "male"
    return "female"


TTS_VOICE_PRESETS: dict[str, dict[str, str]] = {
    "zh-cn": {"female": "Cherry", "male": "Ethan", "language_type": "Chinese"},
    "zh-hk": {"female": "Kiki", "male": "Rocky", "language_type": "Chinese"},
    "zh-sc": {"female": "Sunny", "male": "Eric", "language_type": "Chinese"},
    "zh-bj": {"female": "Cherry", "male": "Dylan", "language_type": "Chinese"},
    "zh-sh": {"female": "Jada", "male": "Ethan", "language_type": "Chinese"},
    "zh-nj": {"female": "Cherry", "male": "Li", "language_type": "Chinese"},
    "zh-sx": {"female": "Cherry", "male": "Marcus", "language_type": "Chinese"},
    "zh-tj": {"female": "Cherry", "male": "Peter", "language_type": "Chinese"},
    "zh-tw": {"female": "Cherry", "male": "Roy", "language_type": "Chinese"},
    "en-us": {"female": "Cherry", "male": "Ethan", "language_type": "English"},
}


def normalize_tts_locale(locale: str) -> str:
    normalized = str(locale or "").strip().lower()
    if normalized.startswith(("zh-hk", "yue", "cantonese", "hk")):
        return "zh-hk"
    if normalized.startswith(("zh-sc", "sichuan", "sc", "sic")):
        return "zh-sc"
    if normalized.startswith(("zh-bj", "beijing", "bj")):
        return "zh-bj"
    if normalized.startswith(("zh-sh", "shanghai", "sh")):
        return "zh-sh"
    if normalized.startswith(("zh-nj", "nanjing", "nj")):
        return "zh-nj"
    if normalized.startswith(("zh-sx", "shanxi", "sx", "shaanxi")):
        return "zh-sx"
    if normalized.startswith(("zh-tj", "tianjin", "tj")):
        return "zh-tj"
    if normalized.startswith(("zh-tw", "zh-hant", "tw", "taiwan", "minnan", "mn")):
        return "zh-tw"
    if normalized.startswith("en"):
        return "en-us"
    if normalized.startswith("zh"):
        return "zh-cn"
    return "zh-cn"


def select_tts_voice(gender: str, locale: str) -> str:
    normalized_gender = normalize_tts_gender(gender)
    normalized_locale = normalize_tts_locale(locale)
    locale_config = TTS_VOICE_PRESETS.get(normalized_locale, TTS_VOICE_PRESETS["zh-cn"])
    return str(locale_config.get(normalized_gender, locale_config["female"])).strip()


def select_tts_language_type(locale: str) -> str:
    normalized_locale = normalize_tts_locale(locale)
    locale_config = TTS_VOICE_PRESETS.get(normalized_locale, TTS_VOICE_PRESETS["zh-cn"])
    return str(locale_config["language_type"]).strip()


def select_realtime_tts_voice(gender: str, locale: str) -> str:
    normalized_gender = normalize_tts_gender(gender)
    normalized_locale = normalize_tts_locale(locale)
    locale_config = TTS_VOICE_PRESETS.get(normalized_locale, TTS_VOICE_PRESETS["zh-cn"])
    return str(locale_config.get(normalized_gender, locale_config["female"])).strip()


def clamp_tts_rate(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = 1.0
    return max(0.6, min(1.4, number))


def clamp_tts_volume(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = 0.85
    return max(0.0, min(1.0, number))


def contains_cjk(text: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", str(text or "")))


def translate_tts_text(text: str, locale: str) -> str:
    normalized_locale = normalize_tts_locale(locale)
    content = str(text or "").strip()
    if not content or normalized_locale != "en-us" or not contains_cjk(content):
        return content
    api_key = OPENAI_API_KEY or TTS_API_KEY
    if not api_key:
        return content

    try:
        request_body = {
            "model": TRANSLATION_MODEL,
            "messages": [
                {"role": "user", "content": content}
            ],
            "translation_options": {
                "source_lang": "Chinese",
                "target_lang": "English"
            }
        }
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        }
        response = read_http_json(f"{OPENAI_BASE_URL}/chat/completions", headers, request_body)
        choices = response.get("choices") if isinstance(response, dict) else None
        if isinstance(choices, list) and choices:
            first_choice = choices[0]
            if isinstance(first_choice, dict):
                message = first_choice.get("message") or {}
                if isinstance(message, dict):
                    translated = str(message.get("content") or "").strip()
                    if translated:
                        return translated
    except Exception:
        return content
    return content


def build_tts_payload(text: str, voice: str, rate: float, language_type: str) -> dict[str, Any]:
    del rate
    return {
        "model": TTS_MODEL,
        "input": {
            "text": text,
            "voice": voice,
            "language_type": language_type,
        }
    }


def build_tts_cache_key(text: str, voice: str, locale: str, rate: float, language_type: str) -> str:
    del rate
    normalized_text = re.sub(r"\s+", " ", str(text or "").strip())
    normalized_locale = str(locale or "").strip().lower()
    return json.dumps(
        {
            "text": normalized_text,
            "voice": str(voice or "").strip(),
            "locale": normalized_locale,
            "languageType": str(language_type or "").strip(),
            "model": TTS_MODEL,
        },
        ensure_ascii=False,
        sort_keys=True,
    )


def get_cached_tts_url(cache_key: str) -> dict[str, Any] | None:
    if TTS_CACHE_TTL_SECONDS <= 0:
        return None
    cached = TTS_URL_CACHE.get(cache_key)
    if not cached:
        return None
    expires_at, payload = cached
    if expires_at <= time.time():
        TTS_URL_CACHE.pop(cache_key, None)
        return None
    return dict(payload)


def set_cached_tts_url(cache_key: str, payload: dict[str, Any]) -> None:
    if TTS_CACHE_TTL_SECONDS <= 0:
        return
    TTS_URL_CACHE[cache_key] = (time.time() + TTS_CACHE_TTL_SECONDS, dict(payload))
    while len(TTS_URL_CACHE) > TTS_CACHE_MAX_SIZE:
        oldest_key = next(iter(TTS_URL_CACHE))
        TTS_URL_CACHE.pop(oldest_key, None)


def read_http_json(url: str, headers: dict[str, str], body: dict[str, Any]) -> dict[str, Any]:
    data = json.dumps(body).encode("utf-8")
    req = request.Request(url=url, data=data, headers=headers, method="POST")
    try:
        with request.urlopen(req, timeout=90) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        raise HTTPException(status_code=exc.code, detail=detail or "云端 TTS 服务返回错误") from exc
    except error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"无法连接云端 TTS 服务: {exc.reason}") from exc


def extract_audio_payload(response: dict[str, Any]) -> tuple[str, str]:
    output = response.get("output") if isinstance(response, dict) else None
    if isinstance(output, dict):
        audio = output.get("audio") or {}
        if isinstance(audio, dict):
            audio_base64 = str(audio.get("data") or "").strip()
            audio_url = str(audio.get("url") or "").strip()
            if audio_base64:
                return audio_base64, "mp3"
            if audio_url:
                return download_audio_as_base64(audio_url), guess_audio_format(audio_url)
        audio_url = str(output.get("audio_url") or output.get("url") or "").strip()
        if audio_url:
            return download_audio_as_base64(audio_url), guess_audio_format(audio_url)
        audio_base64 = str(output.get("audio_base64") or output.get("audioBase64") or "").strip()
        if audio_base64:
            return audio_base64, "mp3"
    raise HTTPException(status_code=502, detail="云端 TTS 未返回可播放音频")


def extract_audio_url(response: dict[str, Any]) -> tuple[str, str]:
    output = response.get("output") if isinstance(response, dict) else None
    if isinstance(output, dict):
        audio = output.get("audio") or {}
        if isinstance(audio, dict):
            audio_url = str(audio.get("url") or "").strip()
            if audio_url:
                return audio_url, guess_audio_format(audio_url)
        audio_url = str(output.get("audio_url") or output.get("url") or "").strip()
        if audio_url:
            return audio_url, guess_audio_format(audio_url)
    raise HTTPException(status_code=502, detail="云端 TTS 未返回音频地址")


def guess_audio_format(audio_url: str) -> str:
    value = str(audio_url or "").lower()
    if ".wav" in value:
        return "wav"
    if ".pcm" in value:
        return "pcm"
    return "mp3"


def download_audio_as_base64(audio_url: str) -> str:
    try:
        with request.urlopen(audio_url, timeout=90) as resp:
            return base64.b64encode(resp.read()).decode("utf-8")
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")
        raise HTTPException(status_code=exc.code, detail=detail or "下载 TTS 音频失败") from exc
    except error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"下载 TTS 音频失败: {exc.reason}") from exc


def stream_dashscope_realtime_tts(payload: TtsRequest):
    try:
        import dashscope
        from dashscope.audio.qwen_tts import SpeechSynthesizer
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="缺少 DashScope 流式 TTS 依赖") from exc

    text = str(payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="播报文本不能为空")

    tts_locale = payload.voiceLocale or payload.locale
    voice = select_tts_voice(payload.voiceGender, tts_locale)
    language_type = select_tts_language_type(tts_locale)
    text = translate_tts_text(text, tts_locale)
    speech_rate = clamp_tts_rate(payload.rate)
    volume = int(round(clamp_tts_volume(payload.volume) * 100))
    emitted = False
    dashscope.api_key = TTS_API_KEY

    try:
        responses = SpeechSynthesizer.call(
            model=TTS_MODEL,
            text=text,
            api_key=TTS_API_KEY,
            voice=voice,
            stream=True,
            audio_format="pcm",
            sample_rate=24000,
            language_type=language_type,
            speech_rate=speech_rate,
            volume=volume,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"云端流式 TTS 初始化失败: {exc}") from exc

    try:
        for item in responses:
            output = item.get("output") if hasattr(item, "get") else None
            if not isinstance(output, dict):
                continue

            audio = output.get("audio") or {}
            audio_data = audio.get("data")
            if not audio_data:
                continue

            try:
                chunk = base64.b64decode(audio_data)
            except Exception as exc:
                if emitted:
                    return
                raise HTTPException(status_code=502, detail=f"云端流式 TTS 音频解码失败: {exc}") from exc

            if not chunk:
                continue

            emitted = True
            yield chunk
    except HTTPException:
        raise
    except Exception as exc:
        if emitted:
            return
        raise HTTPException(status_code=502, detail=f"云端流式 TTS 失败: {exc}") from exc

    if not emitted:
        raise HTTPException(status_code=502, detail="云端流式 TTS 未返回音频数据")


def call_dashscope_tts(payload: TtsRequest) -> dict[str, Any]:
    tts_locale = payload.voiceLocale or payload.locale
    voice = select_tts_voice(payload.voiceGender, tts_locale)
    rate = clamp_tts_rate(payload.rate)
    language_type = select_tts_language_type(tts_locale)
    spoken_text = translate_tts_text(str(payload.text).strip(), tts_locale)
    request_body = build_tts_payload(spoken_text, voice, rate, language_type)
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {TTS_API_KEY}"
    }
    response = read_http_json(TTS_BASE_URL, headers, request_body)
    audio_base64, audio_format = extract_audio_payload(response)
    return {
        "audioBase64": audio_base64,
        "audioFormat": audio_format,
        "voice": voice,
        "model": TTS_MODEL
    }


def call_dashscope_tts_url(payload: TtsRequest) -> dict[str, Any]:
    tts_locale = payload.voiceLocale or payload.locale
    voice = select_tts_voice(payload.voiceGender, tts_locale)
    rate = clamp_tts_rate(payload.rate)
    language_type = select_tts_language_type(tts_locale)
    text = translate_tts_text(str(payload.text).strip(), tts_locale)
    cache_key = build_tts_cache_key(text, voice, tts_locale, rate, language_type)
    cached = get_cached_tts_url(cache_key)
    if cached:
        return cached

    request_body = build_tts_payload(text, voice, rate, language_type)
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {TTS_API_KEY}"
    }
    response = read_http_json(TTS_BASE_URL, headers, request_body)
    audio_url, audio_format = extract_audio_url(response)
    result = {
        "audioUrl": audio_url,
        "audioFormat": audio_format,
        "voice": voice,
        "model": TTS_MODEL
    }
    set_cached_tts_url(cache_key, result)
    return result
