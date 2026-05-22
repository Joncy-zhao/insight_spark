package com.insightspark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

@Service
public class BusinessModelAgentService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern MODEL_NAME_LEADING_PATTERN = Pattern.compile("^(请|请你|帮我|麻烦|需要|我想|想要|帮忙)+");
    private static final Pattern MODEL_NAME_TABLE_PREFIX_PATTERN = Pattern.compile("^基于(?:当前|现有)?[^，。；;\\n]*?(?:表|数据源)?");
    private static final Pattern MODEL_NAME_ACTION_PATTERN = Pattern.compile("(创建|生成|新建|建立|搭建|构建)(一个|一份|个)?");
    private static final Pattern MODEL_NAME_TAIL_SECTION_PATTERN = Pattern.compile("(业务字典|字典|同义词|术语|映射|新增指标公式|增加指标公式|添加指标公式|指标公式|业务公式|公式)\\s*[：:].*");
    private static final Pattern MODEL_NAME_CONJUNCTION_PATTERN = Pattern.compile("(并|然后|之后).*$");
    private static final Pattern MODEL_NAME_PUNCT_PREFIX_PATTERN = Pattern.compile("^[,，;；。:：\\-\\s]+");
    private static final Pattern MODEL_NAME_PUNCT_SUFFIX_PATTERN = Pattern.compile("[,，;；。:：\\-\\s]+$");

    @Autowired
    private DataUploadService dataUploadService;

    @Autowired
    private PythonAiService pythonAiService;

    public Map<String, Object> handleQuestion(Map<String, Object> request) {
        String question = trim(Objects.toString(request == null ? null : request.get("question"), ""));
        if (question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String tableName = trim(Objects.toString(request.get("tableName"), ""));
        if (tableName.isBlank()) {
            tableName = trim(Objects.toString(request.get("selectedTableName"), ""));
        }
        Long activeBusinessModelId = toLong(request.get("activeBusinessModelId"));
        Long lastCreatedBusinessModelId = toLong(request.get("lastCreatedBusinessModelId"));
        Long lastAppliedBusinessModelId = toLong(request.get("lastAppliedBusinessModelId"));

        List<Map<String, Object>> userModels = safeList(dataUploadService.listBusinessModels(false));
        List<Map<String, Object>> enterpriseModels = safeList(dataUploadService.listBusinessModels(true));

        if (looksLikeCreate(question)) {
            return createModel(question, tableName, request);
        }
        if (looksLikePublish(question)) {
            return togglePublish(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId, true);
        }
        if (looksLikeUnpublish(question)) {
            return togglePublish(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId, false);
        }
        if (looksLikeApply(question)) {
            return applyEnterpriseModel(question, tableName, request, userModels, enterpriseModels,
                    activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        }
        if (looksLikePatch(question)) {
            return patchCurrentModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        }
        if (looksLikeExplain(question)) {
            return focusCurrentModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        }
        if (looksLikeDashboard(question)) {
            return dashboardNotReady(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        }

        Map<String, Object> response = baseResponse(question, tableName);
        response.put("message", "暂未识别到业务模型指令");
        return response;
    }

    private Map<String, Object> createModel(String question, String tableName, Map<String, Object> request) {
//        String targetTableName = tableName;
//        if (targetTableName.isBlank()) {
//            targetTableName = trim(Objects.toString(request.get("targetTableName"), ""));
//        }
//        if (targetTableName.isBlank()) {
//            targetTableName = trim(Objects.toString(request.get("selectedTableName"), ""));
//        }
//        if (targetTableName.isBlank()) {
//            throw new IllegalArgumentException("请先选择数据源");
//        }
//
//        String requirement = trim(Objects.toString(request.getOrDefault("requirement", question), ""));
//        String modelName = trim(Objects.toString(request.getOrDefault("modelName", inferModelName(requirement)), ""));
//        List<Map<String, Object>> previewRows = safeList(dataUploadService.preview(targetTableName, 1, 5));
//
//        Map<String, Object> payload = new LinkedHashMap<>();
//        payload.put("tableName", targetTableName);
//        payload.put("requirement", requirement);
//        payload.put("modelName", modelName);
//
//        Map<String, Object> semantic = pythonAiService.businessModelSemantic(
//                question,
//                requirement,
//                targetTableName,
//                dataUploadService.listFields(targetTableName),
//                previewRows
//        ).orElseGet(() -> buildBusinessModelSemanticFallback(question, requirement, targetTableName));

        // 1. 使用一个临时变量来处理赋值逻辑，避免污染要在 lambda 中使用的变量
        String tempTableName = tableName;
        if (tempTableName.isBlank()) {
            tempTableName = trim(Objects.toString(request.get("targetTableName"), ""));
        }
        if (tempTableName.isBlank()) {
            tempTableName = trim(Objects.toString(request.get("selectedTableName"), ""));
        }
        if (tempTableName.isBlank()) {
            throw new IllegalArgumentException("请先选择数据源");
        }

        // 2. 声明为 final 变量，供后续逻辑和 Lambda 表达式安全使用
        final String targetTableName = tempTableName;

        String requirement = trim(Objects.toString(request.getOrDefault("requirement", question), ""));
        List<Map<String, Object>> previewRows = safeList(dataUploadService.preview(targetTableName, 1, 5));
        final String fallbackRequirement = requirement;

        Map<String, Object> semantic = pythonAiService.businessModelSemantic(
                question,
                requirement,
                targetTableName,
                dataUploadService.listFields(targetTableName),
                previewRows
        ).orElseGet(() -> buildBusinessModelSemanticFallback(question, fallbackRequirement, targetTableName));

        String semanticRequirement = trim(Objects.toString(semantic.getOrDefault("requirement", requirement), ""));
        if (!semanticRequirement.isBlank()) {
            requirement = semanticRequirement;
        }
        String requestedModelName = trim(Objects.toString(request.get("modelName"), ""));
        String semanticModelName = trim(Objects.toString(semantic.get("modelName"), ""));
        String modelName = inferBusinessModelName(requestedModelName, semanticModelName, requirement, question);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tableName", targetTableName);
        payload.put("requirement", requirement);
        payload.put("modelName", modelName);

        List<Map<String, Object>> dictionaryEntries = safeListMap(semantic.get("dictionaryEntries"));
        List<Map<String, Object>> metricDefinitions = safeListMap(semantic.get("metricDefinitions"));
        if (!dictionaryEntries.isEmpty()) {
            payload.put("dictionaryEntries", dictionaryEntries);
        }
        if (!metricDefinitions.isEmpty()) {
            payload.put("metricDefinitions", metricDefinitions);
        }

        Map<String, Object> created = dataUploadService.createBusinessModel(payload);
        Map<String, Object> response = baseResponse(question, targetTableName);
        response.put("intent", "CREATE_MODEL");
        response.put("handled", true);
        response.put("actionStatus", "SUCCESS");
        response.put("message", "已创建业务模型「" + Objects.toString(created.get("modelName"), modelName) + "」，并打开维护抽屉");
        response.put("modelId", created.get("id"));
        response.put("focusModelId", created.get("id"));
        response.put("modelName", created.get("modelName"));
        response.put("targetTableName", targetTableName);
        response.put("activeBusinessModelId", created.get("id"));
        response.put("lastCreatedBusinessModelId", created.get("id"));
        response.put("openBusinessDictionary", true);
        response.put("refreshBusinessModels", true);
        response.put("createdModel", created);
        response.put("reasoning", semantic.getOrDefault("reasoning", List.of()));
        return response;
    }

    private Map<String, Object> togglePublish(String question,
                                              String tableName,
                                              List<Map<String, Object>> userModels,
                                              Long activeBusinessModelId,
                                              Long lastCreatedBusinessModelId,
                                              Long lastAppliedBusinessModelId,
                                              boolean published) {
        Map<String, Object> model = resolveModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (model == null) {
            throw new IllegalArgumentException(published ? "没有找到可发布的业务模型" : "没有找到可取消发布的业务模型");
        }
        Long modelId = toLong(model.get("id"));
        if (modelId == null) {
            throw new IllegalArgumentException("业务模型标识无效");
        }
        dataUploadService.publishBusinessModel(modelId, published);
        Map<String, Object> response = baseResponse(question, Objects.toString(model.get("tableName"), ""));
        response.put("intent", published ? "PUBLISH_MODEL" : "UNPUBLISH_MODEL");
        response.put("handled", true);
        response.put("actionStatus", "SUCCESS");
        response.put("message", published
                ? "已将业务模型「" + Objects.toString(model.get("modelName"), "") + "」发布到企业模型库"
                : "已取消发布业务模型「" + Objects.toString(model.get("modelName"), "") + "」");
        response.put("modelId", modelId);
        response.put("focusModelId", modelId);
        response.put("modelName", model.get("modelName"));
        response.put("tableName", model.get("tableName"));
        response.put("activeBusinessModelId", modelId);
        response.put("openBusinessDictionary", true);
        response.put("refreshBusinessModels", true);
        return response;
    }

    private Map<String, Object> applyEnterpriseModel(String question,
                                                     String targetTableName,
                                                     Map<String, Object> request,
                                                     List<Map<String, Object>> userModels,
                                                     List<Map<String, Object>> enterpriseModels,
                                                     Long activeBusinessModelId,
                                                     Long lastCreatedBusinessModelId,
                                                     Long lastAppliedBusinessModelId) {
        String resolvedTargetTableName = targetTableName;
        if (resolvedTargetTableName.isBlank()) {
            resolvedTargetTableName = trim(Objects.toString(request.get("targetTableName"), ""));
        }
        if (resolvedTargetTableName.isBlank()) {
            resolvedTargetTableName = trim(Objects.toString(request.get("selectedTableName"), ""));
        }
        if (resolvedTargetTableName.isBlank()) {
            resolvedTargetTableName = resolveTableNameFromContext(userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        }
        if (resolvedTargetTableName.isBlank()) {
            throw new IllegalArgumentException("请先选择要套用的目标数据源");
        }

        Map<String, Object> enterpriseModel = resolveEnterpriseModel(question, enterpriseModels, userModels,
                activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (enterpriseModel == null) {
            throw new IllegalArgumentException("没有找到可套用的企业模型");
        }
        Long sourceModelId = toLong(enterpriseModel.get("id"));
        if (sourceModelId == null) {
            throw new IllegalArgumentException("企业模型标识无效");
        }

        Map<String, Object> applied = dataUploadService.applyBusinessModel(sourceModelId, resolvedTargetTableName);
        Map<String, Object> response = baseResponse(question, resolvedTargetTableName);
        response.put("intent", "APPLY_ENTERPRISE_MODEL");
        response.put("handled", true);
        response.put("actionStatus", "SUCCESS");
        response.put("message", "已将企业模型「" + Objects.toString(enterpriseModel.get("modelName"), "") + "」套用到当前数据源，并生成你的副本");
        response.put("modelId", sourceModelId);
        response.put("focusModelId", applied.get("id"));
        response.put("appliedModelId", applied.get("id"));
        response.put("appliedModelName", applied.get("modelName"));
        response.put("modelName", enterpriseModel.get("modelName"));
        response.put("targetTableName", resolvedTargetTableName);
        response.put("activeBusinessModelId", applied.get("id"));
        response.put("lastAppliedBusinessModelId", applied.get("id"));
        response.put("openBusinessDictionary", true);
        response.put("refreshBusinessModels", true);
        response.put("sourceModel", enterpriseModel);
        response.put("appliedModel", applied);
        return response;
    }

    private Map<String, Object> focusCurrentModel(String question,
                                                  String tableName,
                                                  List<Map<String, Object>> userModels,
                                                  Long activeBusinessModelId,
                                                  Long lastCreatedBusinessModelId,
                                                  Long lastAppliedBusinessModelId) {
        Map<String, Object> model = resolveModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (model == null) {
            throw new IllegalArgumentException("没有找到可定位的业务模型");
        }
        Long modelId = toLong(model.get("id"));
        if (modelId == null) {
            throw new IllegalArgumentException("业务模型标识无效");
        }
        Map<String, Object> response = baseResponse(question, Objects.toString(model.get("tableName"), ""));
        response.put("intent", looksLikePatch(question) ? "PATCH_MODEL" : "EXPLAIN_MODEL");
        response.put("handled", true);
        response.put("actionStatus", "SUCCESS");
        response.put("message", "已定位到业务模型「" + Objects.toString(model.get("modelName"), "") + "」，可在右侧抽屉继续维护字典、公式和维度");
        response.put("modelId", modelId);
        response.put("focusModelId", modelId);
        response.put("modelName", model.get("modelName"));
        response.put("tableName", model.get("tableName"));
        response.put("activeBusinessModelId", modelId);
        response.put("openBusinessDictionary", true);
        response.put("refreshBusinessModels", true);
        return response;
    }

    private Map<String, Object> patchCurrentModel(String question,
                                                  String tableName,
                                                  List<Map<String, Object>> userModels,
                                                  Long activeBusinessModelId,
                                                  Long lastCreatedBusinessModelId,
                                                  Long lastAppliedBusinessModelId) {
        Map<String, Object> model = resolveModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (model == null) {
            throw new IllegalArgumentException("没有找到可修改的业务模型");
        }
        Long modelId = toLong(model.get("id"));
        if (modelId == null) {
            throw new IllegalArgumentException("业务模型标识无效");
        }

        Map<String, Object> detail = dataUploadService.getBusinessModelDetail(modelId);
        String resolvedTableName = trim(Objects.toString(detail.get("tableName"), ""));
        Map<String, Object> modelJson = parseModelJson(detail.get("modelJson"));
        List<Map<String, Object>> fields = safeList(dataUploadService.listFields(resolvedTableName));
        List<Map<String, Object>> previewRows = safeList(dataUploadService.preview(resolvedTableName, 1, 5));
        List<Map<String, Object>> existingDictionaryEntries = safeListMap(modelJson.get("dictionaryEntries"));
        List<Map<String, Object>> existingMetricDefinitions = safeListMap(modelJson.get("metricDefinitions"));
        List<Map<String, Object>> existingDimensionDefinitions = safeListMap(modelJson.get("dimensionSystem"));

        Map<String, Object> patch = pythonAiService.businessModelPatch(
                question,
                resolvedTableName,
                trim(Objects.toString(detail.get("modelName"), "")),
                trim(Objects.toString(detail.get("modelRequirement"), "")),
                existingDictionaryEntries,
                existingMetricDefinitions,
                existingDimensionDefinitions,
                fields,
                previewRows
        ).orElseGet(() -> buildBusinessModelPatchFallback(question));

        List<Map<String, Object>> operations = filterPatchOperationsForQuestion(
                question,
                safeListMap(patch.get("operations"))
        );
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("未识别到可执行的模型修改动作，请明确说明要新增、修改或删除哪个指标、公式或业务字典映射");
        }

        List<Map<String, Object>> effectiveOperations = materializeFieldBindingOperations(
                operations,
                existingDictionaryEntries,
                existingMetricDefinitions,
                existingDimensionDefinitions
        );

        List<Map<String, Object>> mergedDictionaryEntries = mergeDictionaryEntries(existingDictionaryEntries, effectiveOperations);
        List<Map<String, Object>> mergedMetricDefinitions = mergeMetricDefinitions(existingMetricDefinitions, effectiveOperations);
        List<Map<String, Object>> mergedDimensionDefinitions = mergeDimensionDefinitions(existingDimensionDefinitions, effectiveOperations);

        Map<String, Object> updateRequest = new LinkedHashMap<>();
        updateRequest.put("modelName", detail.get("modelName"));
        updateRequest.put("modelRequirement", detail.get("modelRequirement"));
        updateRequest.put("dictionaryEntries", mergedDictionaryEntries);
        updateRequest.put("metricDefinitions", mergedMetricDefinitions);
        updateRequest.put("dimensionSystem", mergedDimensionDefinitions);

        Map<String, Object> updated = dataUploadService.updateBusinessModel(modelId, updateRequest);
        String resolvedIntent = inferResponseIntent(question, patch, operations, effectiveOperations);
        List<Map<String, Object>> bindingResults = collectBindingResults(
                effectiveOperations,
                mergedDictionaryEntries,
                mergedMetricDefinitions,
                mergedDimensionDefinitions,
                fields
        );
        Map<String, Object> response = baseResponse(question, resolvedTableName);
        response.put("intent", resolvedIntent);
        response.put("handled", true);
        response.put("actionStatus", "SUCCESS");
        response.put("message", buildPatchMessage(
                resolvedIntent,
                Objects.toString(updated.get("modelName"), Objects.toString(detail.get("modelName"), "")),
                bindingResults.size(),
                operations.size()
        ));
        response.put("modelId", modelId);
        response.put("focusModelId", modelId);
        response.put("modelName", updated.get("modelName"));
        response.put("tableName", resolvedTableName);
        response.put("activeBusinessModelId", modelId);
        response.put("openBusinessDictionary", true);
        response.put("refreshBusinessModels", true);
        response.put("reasoning", patch.getOrDefault("reasoning", List.of()));
        response.put("operations", effectiveOperations);
        response.put("fieldBindingResults", bindingResults);
        response.put("updatedModel", updated);
        return response;
    }

    private Map<String, Object> dashboardNotReady(String question,
                                                  String tableName,
                                                  List<Map<String, Object>> userModels,
                                                  Long activeBusinessModelId,
                                                  Long lastCreatedBusinessModelId,
                                                  Long lastAppliedBusinessModelId) {
        Map<String, Object> model = resolveModel(question, tableName, userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        Map<String, Object> response = baseResponse(question, Objects.toString(model == null ? null : model.get("tableName"), ""));
        response.put("intent", "GENERATE_DASHBOARD_FROM_MODEL");
        response.put("handled", true);
        response.put("actionStatus", "NOT_READY");
        response.put("message", model == null
                ? "一键生成全链路看板的入口已接入，但当前还没有定位到可用业务模型"
                : "已定位到业务模型「" + Objects.toString(model.get("modelName"), "") + "」，但一键生成看板仍在后续阶段完善");
        response.put("modelId", model == null ? null : model.get("id"));
        response.put("focusModelId", model == null ? null : model.get("id"));
        response.put("modelName", model == null ? null : model.get("modelName"));
        response.put("tableName", model == null ? null : model.get("tableName"));
        response.put("openBusinessDictionary", model != null);
        response.put("refreshBusinessModels", false);
        return response;
    }

    private Map<String, Object> resolveEnterpriseModel(String question,
                                                       List<Map<String, Object>> enterpriseModels,
                                                       List<Map<String, Object>> userModels,
                                                       Long activeBusinessModelId,
                                                       Long lastCreatedBusinessModelId,
                                                       Long lastAppliedBusinessModelId) {
        Map<String, Object> bestEnterprise = findBestMatch(question, enterpriseModels);
        if (bestEnterprise != null) {
            return bestEnterprise;
        }

        Map<String, Object> contextual = resolveContextModel(userModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (contextual != null && isPronounReference(question)) {
            return contextual;
        }

        return resolveContextModel(enterpriseModels, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
    }

    private Map<String, Object> resolveModel(String question,
                                             String tableName,
                                             List<Map<String, Object>> models,
                                             Long activeBusinessModelId,
                                             Long lastCreatedBusinessModelId,
                                             Long lastAppliedBusinessModelId) {
        Map<String, Object> bestMatch = findBestMatch(question, models);
        if (bestMatch != null) {
            return bestMatch;
        }
        if (isPronounReference(question) || isShortAction(question)) {
            Map<String, Object> contextual = resolveContextModel(models, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
            if (contextual != null) {
                return contextual;
            }
        }
        Map<String, Object> contextual = resolveContextModel(models, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        if (contextual != null) {
            return contextual;
        }
        Map<String, Object> latestByTable = findLatestModelByTableName(models, tableName);
        if (latestByTable != null) {
            return latestByTable;
        }
        if (isPronounReference(question) || isShortAction(question)) {
            return findLatestModel(models);
        }
        return null;
    }

    private Map<String, Object> resolveContextModel(List<Map<String, Object>> models,
                                                    Long activeBusinessModelId,
                                                    Long lastCreatedBusinessModelId,
                                                    Long lastAppliedBusinessModelId) {
        Map<String, Object> byId = findById(models, activeBusinessModelId);
        if (byId != null) {
            return byId;
        }
        byId = findById(models, lastCreatedBusinessModelId);
        if (byId != null) {
            return byId;
        }
        return findById(models, lastAppliedBusinessModelId);
    }

    private String resolveTableNameFromContext(List<Map<String, Object>> models,
                                               Long activeBusinessModelId,
                                               Long lastCreatedBusinessModelId,
                                               Long lastAppliedBusinessModelId) {
        Map<String, Object> model = resolveContextModel(models, activeBusinessModelId, lastCreatedBusinessModelId, lastAppliedBusinessModelId);
        return model == null ? "" : trim(Objects.toString(model.get("tableName"), ""));
    }

    private Map<String, Object> findLatestModelByTableName(List<Map<String, Object>> models, String tableName) {
        String normalizedTableName = normalize(tableName);
        if (normalizedTableName.isBlank() || models == null || models.isEmpty()) {
            return null;
        }
        for (Map<String, Object> model : models) {
            if (normalizedTableName.equals(normalize(Objects.toString(model.get("tableName"), "")))) {
                return model;
            }
        }
        return null;
    }

    private Map<String, Object> findLatestModel(List<Map<String, Object>> models) {
        if (models == null || models.isEmpty()) {
            return null;
        }
        return models.get(0);
    }

    private Map<String, Object> findById(List<Map<String, Object>> models, Long id) {
        if (id == null) {
            return null;
        }
        for (Map<String, Object> model : models) {
            if (Objects.equals(toLong(model.get("id")), id)) {
                return model;
            }
        }
        return null;
    }

    private Map<String, Object> findBestMatch(String question, List<Map<String, Object>> models) {
        if (models == null || models.isEmpty()) {
            return null;
        }
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return null;
        }

        List<ScoredModel> scored = new ArrayList<>();
        for (Map<String, Object> model : models) {
            int score = scoreModelMatch(normalizedQuestion, model);
            if (score > 0) {
                scored.add(new ScoredModel(model, score));
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort(Comparator.comparingInt(ScoredModel::score).reversed()
                .thenComparing(item -> Objects.toString(item.model().get("updatedAt"), ""), Comparator.reverseOrder()));
        return scored.get(0).model();
    }

    private int scoreModelMatch(String normalizedQuestion, Map<String, Object> model) {
        String modelName = normalize(Objects.toString(model.get("modelName"), ""));
        String modelNameShort = normalize(stripModelSuffix(Objects.toString(model.get("modelName"), "")));
        String tableName = normalize(Objects.toString(model.get("tableName"), ""));
        String requirement = normalize(Objects.toString(model.get("modelRequirement"), ""));

        int score = 0;
        if (!modelName.isBlank() && normalizedQuestion.equals(modelName)) {
            score = Math.max(score, 100);
        }
        if (!modelNameShort.isBlank() && normalizedQuestion.contains(modelNameShort)) {
            score = Math.max(score, 92);
        }
        if (!modelName.isBlank() && normalizedQuestion.contains(modelName)) {
            score = Math.max(score, 90);
        }
        if (!tableName.isBlank() && normalizedQuestion.contains(tableName)) {
            score = Math.max(score, 70);
        }
        if (!requirement.isBlank()) {
            String[] tokens = requirement.split("(?<!^)(?=[A-Z])|\\s+");
            for (String token : tokens) {
                String normalizedToken = normalize(token);
                if (!normalizedToken.isBlank() && normalizedQuestion.contains(normalizedToken)) {
                    score = Math.max(score, 60);
                }
            }
        }
        return score;
    }

    private boolean looksLikeCreate(String question) {
        return containsAny(question, "创建", "新建", "生成", "搭建", "构建", "建模", "做一个", "做个", "建一个")
                && containsAny(question, "模型", "业务", "字典", "公式", "指标", "维度");
    }

    private boolean looksLikePublish(String question) {
        return containsAny(question, "发布到企业模型库", "发布到企业库", "发布", "上架");
    }

    private boolean looksLikeUnpublish(String question) {
        return containsAny(question, "取消发布", "下架", "撤回发布");
    }

    private boolean looksLikeApply(String question) {
        return containsAny(question, "套用", "复用", "应用", "迁移", "引用", "复制")
                && containsAny(question, "模型", "企业模型库", "企业模型", "这个", "它", "当前", "当前数据源");
    }

    private boolean looksLikePatch(String question) {
        boolean hasPatchVerb = containsAny(question, "修改", "更新", "编辑", "调整", "补充", "完善", "新增", "改一下", "改成", "修正",
                "删除", "移除", "去掉", "取消",
                "绑定到", "绑定为", "绑定至",
                "映射到", "映射为", "映射至",
                "对应到", "对应为", "对应至",
                "改绑", "重新绑定");
        boolean hasPatchTarget = containsAny(question, "模型", "字典", "公式", "指标", "维度", "业务", "字段");
        return hasPatchVerb && (hasPatchTarget || looksLikeExplicitFieldBindingMutation(question));
    }

    private boolean looksLikeExplain(String question) {
        return containsAny(question, "解释", "说明", "展开", "拆解", "讲一下", "梳理")
                && containsAny(question, "模型", "字典", "公式", "指标", "维度");
    }

    private boolean looksLikeDashboard(String question) {
        return containsAny(question, "看板", "仪表盘", "大屏", "可视化")
                && containsAny(question, "模型", "业务", "生成", "基于");
    }

    private boolean isPronounReference(String question) {
        return containsAny(question, "这个", "这个模型", "它", "该模型", "当前模型", "刚才", "上一个", "刚建", "刚创建", "前一个");
    }

    private boolean isShortAction(String question) {
        String text = trim(question);
        return text.length() <= 4 && containsAny(text, "发布", "取消发布", "套用", "应用", "保存", "更新", "修改", "编辑", "完善");
    }

    private boolean containsAny(String question, String... keywords) {
        String text = trim(question);
        if (text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String inferBusinessModelName(String requestModelName, String semanticModelName, String requirement, String question) {
        String cleanedRequestName = cleanBusinessModelName(requestModelName);
        if (!cleanedRequestName.isBlank()) {
            return ensureModelSuffix(cleanedRequestName);
        }

        String cleanedSemanticName = cleanBusinessModelName(semanticModelName);
        if (!cleanedSemanticName.isBlank()) {
            return ensureModelSuffix(cleanedSemanticName);
        }

        String requirementSubject = extractBusinessSubject(requirement);
        if (!requirementSubject.isBlank()) {
            return ensureModelSuffix(requirementSubject);
        }

        String questionSubject = extractBusinessSubject(question);
        if (!questionSubject.isBlank()) {
            return ensureModelSuffix(questionSubject);
        }

        return "零代码业务模型";
    }

    private String extractBusinessSubject(String text) {
        String value = trim(text);
        if (value.isBlank()) {
            return "";
        }

        String[] patterns = new String[] {
                "(?:创建|生成|新建|建立|搭建|构建|做一个|建一个)([^，。；;\\n]*?模型)",
                "(?:做|建|搭建|构建)([^，。；;\\n]*?分析)",
                "([\\u4e00-\\u9fa5A-Za-z0-9_]{2,18}(?:分析|画像|看板|专题|经营|运营|复购|生命周期))(?:模型)?"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value);
            if (matcher.find()) {
                String candidate = cleanBusinessModelName(matcher.group(1));
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return cleanBusinessModelName(value);
    }

    private String cleanBusinessModelName(String text) {
        String value = trim(text);
        if (value.isBlank()) {
            return "";
        }

        value = value.replaceAll("[\\r\\n\\t]+", " ");
        value = value.replaceAll("[“”\"'`<>]", "");
        value = value.replaceAll("\\s+", " ").trim();
        value = MODEL_NAME_LEADING_PATTERN.matcher(value).replaceFirst("");
        value = MODEL_NAME_TABLE_PREFIX_PATTERN.matcher(value).replaceFirst("");
        value = value.replaceFirst("^(围绕|针对|面向)", "");
        value = MODEL_NAME_ACTION_PATTERN.matcher(value).replaceAll("");
        value = MODEL_NAME_TAIL_SECTION_PATTERN.matcher(value).replaceAll("");
        value = MODEL_NAME_CONJUNCTION_PATTERN.matcher(value).replaceAll("");
        value = value.replaceFirst("(需求|场景|内容|能力)$", "");
        value = MODEL_NAME_PUNCT_PREFIX_PATTERN.matcher(value).replaceFirst("");
        value = MODEL_NAME_PUNCT_SUFFIX_PATTERN.matcher(value).replaceFirst("");

        value = value
                .replace("当前", "")
                .replace("销售明细表", "")
                .replace("数据表", "")
                .replace("明细表", "")
                .replace("数据源", "")
                .replace("业务模型", "")
                .replace("模型搭建", "")
                .replace("进行", "")
                .replace("一个", "")
                .trim();
        value = value.replaceFirst("^(基于|按照|按|对|将)", "").trim();
        if (value.endsWith("模型模型")) {
            value = value.substring(0, value.length() - 2);
        }
        if (value.length() > 16) {
            value = value.substring(0, 16).trim();
        }
        return value;
    }

    private String ensureModelSuffix(String text) {
        String value = trim(text);
        if (value.isBlank()) {
            return "零代码业务模型";
        }
        if (!value.endsWith("模型")) {
            value = value + "模型";
        }
        if (value.length() > 18) {
            value = value.substring(0, 18).trim();
        }
        return value;
    }

    private Map<String, Object> baseResponse(String question, String tableName) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("tableName", tableName);
        response.put("handled", false);
        response.put("intent", "UNKNOWN");
        response.put("actionStatus", "IGNORED");
        response.put("openBusinessDictionary", false);
        response.put("refreshBusinessModels", false);
        return response;
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeListMap(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(Objects.toString(entry.getKey(), ""), entry.getValue());
                }
                result.add(copy);
            }
        }
        return result;
    }

    private Map<String, Object> buildBusinessModelSemanticFallback(String question, String requirement, String tableName) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requirement", trim(requirement));
        response.put("modelName", inferBusinessModelName("", "", requirement, question));
        response.put("dictionaryEntries", List.of());
        response.put("metricDefinitions", List.of());
        response.put("reasoning", List.of("AI 语义拆解不可用，已保守返回空字典/空公式"));
        response.put("confidence", 0.0);
        response.put("tableName", tableName);
        response.put("question", question);
        return response;
    }

    private Map<String, Object> buildBusinessModelPatchFallback(String question) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("intent", "PATCH_MODEL");
        response.put("operations", List.of());
        response.put("reasoning", List.of("AI 模型修改语义拆解不可用，且规则兜底未识别到明确操作"));
        response.put("confidence", 0.0);
        response.put("question", question);
        return response;
    }

    private Map<String, Object> parseModelJson(Object rawValue) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                result.put(Objects.toString(entry.getKey(), ""), entry.getValue());
            }
            return result;
        }
        String text = trim(Objects.toString(rawValue, ""));
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(text, Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("业务模型内容解析失败");
        }
    }

    private List<Map<String, Object>> mergeDictionaryEntries(List<Map<String, Object>> existingEntries,
                                                             List<Map<String, Object>> operations) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> entry : existingEntries) {
            Map<String, Object> normalized = normalizeDictionaryEntry(entry);
            String key = dictionaryEntryKey(normalized);
            if (!key.isBlank()) {
                merged.put(key, normalized);
            }
        }
        for (Map<String, Object> operation : operations) {
            if (!"dictionaryEntry".equals(Objects.toString(operation.get("targetType"), ""))) {
                continue;
            }
            Map<String, Object> normalized = normalizeDictionaryEntry(operation);
            String key = dictionaryEntryKey(normalized);
            if (key.isBlank()) {
                continue;
            }
            String action = trim(Objects.toString(operation.get("action"), "UPSERT")).toUpperCase(Locale.ROOT);
            if ("DELETE".equals(action)) {
                merged.remove(key);
            } else {
                merged.put(key, normalized);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<Map<String, Object>> mergeMetricDefinitions(List<Map<String, Object>> existingEntries,
                                                             List<Map<String, Object>> operations) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> entry : existingEntries) {
            Map<String, Object> normalized = normalizeMetricDefinition(entry);
            String key = metricDefinitionKey(normalized);
            if (!key.isBlank()) {
                merged.put(key, normalized);
            }
        }
        for (Map<String, Object> operation : operations) {
            if (!"metricDefinition".equals(Objects.toString(operation.get("targetType"), ""))) {
                continue;
            }
            Map<String, Object> normalized = normalizeMetricDefinition(operation);
            String key = metricDefinitionKey(normalized);
            if (key.isBlank()) {
                continue;
            }
            String action = trim(Objects.toString(operation.get("action"), "UPSERT")).toUpperCase(Locale.ROOT);
            if ("DELETE".equals(action)) {
                merged.remove(key);
            } else {
                merged.put(key, normalized);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<Map<String, Object>> mergeDimensionDefinitions(List<Map<String, Object>> existingEntries,
                                                                List<Map<String, Object>> operations) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> entry : existingEntries) {
            Map<String, Object> normalized = normalizeDimensionDefinition(entry);
            String key = dimensionDefinitionKey(normalized);
            if (!key.isBlank()) {
                merged.put(key, normalized);
            }
        }
        for (Map<String, Object> operation : operations) {
            String targetType = Objects.toString(operation.get("targetType"), "");
            if (!"fieldBinding".equals(targetType) && !"dimensionDefinition".equals(targetType)) {
                continue;
            }
            String bindingType = trim(Objects.toString(operation.get("bindingType"), ""));
            if ("fieldBinding".equals(targetType)
                    && !bindingType.equalsIgnoreCase("dimensionDefinition")
                    && !bindingType.equalsIgnoreCase("AUTO")) {
                continue;
            }
            Map<String, Object> normalized = normalizeDimensionDefinition(operation);
            String key = dimensionDefinitionKey(normalized);
            if (key.isBlank()) {
                continue;
            }
            String action = trim(Objects.toString(operation.get("action"), "UPSERT")).toUpperCase(Locale.ROOT);
            if ("DELETE".equals(action)) {
                merged.remove(key);
            } else {
                merged.put(key, normalized);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<Map<String, Object>> materializeFieldBindingOperations(List<Map<String, Object>> operations,
                                                                        List<Map<String, Object>> existingDictionaryEntries,
                                                                        List<Map<String, Object>> existingMetricDefinitions,
                                                                        List<Map<String, Object>> existingDimensionDefinitions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String targetType = trim(Objects.toString(operation.get("targetType"), ""));
            if (!"fieldBinding".equals(targetType)) {
                result.add(operation);
                continue;
            }
            String resolvedType = resolveFieldBindingOperationType(operation, existingDictionaryEntries, existingMetricDefinitions, existingDimensionDefinitions);
            String action = trim(Objects.toString(operation.get("action"), "UPSERT")).toUpperCase(Locale.ROOT);
            String name = resolveBindingName(operation);
            String field = trim(Objects.toString(operation.get("field"), ""));
            if (name.isBlank()) {
                continue;
            }
            switch (resolvedType) {
                case "dictionaryEntry" -> {
                    Map<String, Object> existing = findByName(existingDictionaryEntries, "term", name);
                    Map<String, Object> materialized = new LinkedHashMap<>();
                    materialized.put("targetType", "dictionaryEntry");
                    materialized.put("action", action);
                    materialized.put("term", name);
                    materialized.put("field", field.isBlank() && existing != null
                            ? trim(Objects.toString(existing.get("field"), ""))
                            : field);
                    materialized.put("synonyms", existing == null ? "" : normalizeSynonyms(existing.get("synonyms")));
                    result.add(materialized);
                }
                case "dimensionDefinition" -> {
                    Map<String, Object> existing = findByName(existingDimensionDefinitions, "name", name);
                    Map<String, Object> materialized = new LinkedHashMap<>();
                    materialized.put("targetType", "dimensionDefinition");
                    materialized.put("action", action);
                    materialized.put("name", name);
                    materialized.put("field", field.isBlank() && existing != null
                            ? trim(Objects.toString(existing.get("field"), ""))
                            : field);
                    result.add(materialized);
                }
                default -> {
                    Map<String, Object> existing = findByName(existingMetricDefinitions, "name", name);
                    Map<String, Object> materialized = new LinkedHashMap<>();
                    materialized.put("targetType", "metricDefinition");
                    materialized.put("action", action);
                    materialized.put("name", name);
                    materialized.put("field", field.isBlank() && existing != null
                            ? trim(Objects.toString(existing.get("field"), ""))
                            : field);
                    materialized.put("aggregation", existing == null
                            ? "SUM"
                            : trim(Objects.toString(existing.get("aggregation"), "SUM")).toUpperCase(Locale.ROOT));
                    materialized.put("formula", existing == null
                            ? field
                            : trim(Objects.toString(existing.get("formula"), field)));
                    result.add(materialized);
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> filterPatchOperationsForQuestion(String question, List<Map<String, Object>> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        boolean dictionaryMutation = looksLikeExplicitDictionaryMutation(question);
        boolean formulaMutation = looksLikeExplicitFormulaMutation(question);
        boolean fieldBindingMutation = looksLikeExplicitFieldBindingMutation(question);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String targetType = trim(Objects.toString(operation.get("targetType"), ""));
            if (dictionaryMutation && !formulaMutation) {
                if ("dictionaryEntry".equals(targetType)) {
                    filtered.add(operation);
                } else if (isDictionaryFieldBinding(operation)) {
                    filtered.add(withBindingType(operation, "dictionaryEntry"));
                }
                continue;
            }
            if (formulaMutation && !dictionaryMutation) {
                if ("metricDefinition".equals(targetType)) {
                    filtered.add(operation);
                } else if (isMetricFieldBinding(operation)) {
                    filtered.add(withBindingType(operation, "metricDefinition"));
                }
                continue;
            }
            if (fieldBindingMutation && "fieldBinding".equals(targetType)) {
                filtered.add(operation);
                continue;
            }
            if (!"fieldBinding".equals(targetType)) {
                filtered.add(operation);
            }
        }
        if (filtered.isEmpty() && (dictionaryMutation || formulaMutation || fieldBindingMutation)) {
            return List.of();
        }
        return filtered.isEmpty() ? operations : filtered;
    }

    private Map<String, Object> withBindingType(Map<String, Object> operation, String bindingType) {
        Map<String, Object> copy = new LinkedHashMap<>(operation);
        copy.put("bindingType", bindingType);
        return copy;
    }

    private boolean isDictionaryFieldBinding(Map<String, Object> operation) {
        String targetType = trim(Objects.toString(operation.get("targetType"), ""));
        String bindingType = trim(Objects.toString(operation.get("bindingType"), ""));
        return "fieldBinding".equals(targetType)
                && (bindingType.isBlank() || "AUTO".equalsIgnoreCase(bindingType) || "dictionaryEntry".equals(bindingType));
    }

    private boolean isMetricFieldBinding(Map<String, Object> operation) {
        String targetType = trim(Objects.toString(operation.get("targetType"), ""));
        String bindingType = trim(Objects.toString(operation.get("bindingType"), ""));
        return "fieldBinding".equals(targetType) && "metricDefinition".equals(bindingType);
    }

    private Map<String, Object> normalizeDictionaryEntry(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        String term = trim(Objects.toString(raw.get("term"), Objects.toString(raw.get("name"), "")));
        normalized.put("term", term);
        normalized.put("field", trim(Objects.toString(raw.get("field"), "")));
        normalized.put("synonyms", normalizeSynonyms(raw.get("synonyms")));
        return normalized;
    }

    private Map<String, Object> normalizeMetricDefinition(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", trim(Objects.toString(raw.get("name"), Objects.toString(raw.get("term"), ""))));
        normalized.put("field", trim(Objects.toString(raw.get("field"), "")));
        String aggregation = trim(Objects.toString(raw.get("aggregation"), "SUM")).toUpperCase(Locale.ROOT);
        normalized.put("aggregation", aggregation.isBlank() ? "SUM" : aggregation);
        normalized.put("formula", trim(Objects.toString(raw.get("formula"), "")));
        return normalized;
    }

    private Map<String, Object> normalizeDimensionDefinition(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", trim(Objects.toString(raw.get("name"), Objects.toString(raw.get("term"), ""))));
        normalized.put("field", trim(Objects.toString(raw.get("field"), "")));
        return normalized;
    }

    private String dictionaryEntryKey(Map<String, Object> entry) {
        return normalize(Objects.toString(entry.get("term"), ""));
    }

    private String metricDefinitionKey(Map<String, Object> entry) {
        return normalize(Objects.toString(entry.get("name"), ""));
    }

    private String dimensionDefinitionKey(Map<String, Object> entry) {
        return normalize(Objects.toString(entry.get("name"), ""));
    }

    private String inferPatchIntent(Map<String, Object> patch, List<Map<String, Object>> operations) {
        String intent = trim(Objects.toString(patch.get("intent"), "")).toUpperCase(Locale.ROOT);
        if ("BIND_FIELDS".equals(intent)) {
            return intent;
        }
        boolean hasFieldBinding = false;
        boolean hasOtherOperation = false;
        for (Map<String, Object> operation : operations) {
            String targetType = trim(Objects.toString(operation.get("targetType"), ""));
            if ("fieldBinding".equals(targetType)) {
                hasFieldBinding = true;
            } else if (!targetType.isBlank()) {
                hasOtherOperation = true;
            }
        }
        if (hasFieldBinding && !hasOtherOperation) {
            return "BIND_FIELDS";
        }
        return "PATCH_MODEL";
    }

    private String inferResponseIntent(String question,
                                       Map<String, Object> patch,
                                       List<Map<String, Object>> rawOperations,
                                       List<Map<String, Object>> effectiveOperations) {
        if (looksLikeExplicitDictionaryMutation(question) || looksLikeExplicitFormulaMutation(question)) {
            return "PATCH_MODEL";
        }
        String rawIntent = inferPatchIntent(patch, rawOperations);
        if ("BIND_FIELDS".equals(rawIntent)) {
            return rawIntent;
        }
        return inferPatchIntent(patch, effectiveOperations);
    }

    private List<Map<String, Object>> collectBindingResults(List<Map<String, Object>> operations,
                                                            List<Map<String, Object>> dictionaryEntries,
                                                            List<Map<String, Object>> metricDefinitions,
                                                            List<Map<String, Object>> dimensionDefinitions,
                                                            List<Map<String, Object>> fields) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String targetType = trim(Objects.toString(operation.get("targetType"), ""));
            String bindingType = trim(Objects.toString(operation.get("bindingType"), ""));
            if (!"fieldBinding".equals(targetType)
                    && !"dictionaryEntry".equals(targetType)
                    && !"metricDefinition".equals(targetType)
                    && !"dimensionDefinition".equals(targetType)) {
                continue;
            }
            String action = trim(Objects.toString(operation.get("action"), "UPSERT")).toUpperCase(Locale.ROOT);
            String name = resolveBindingName(operation);
            String field = trim(Objects.toString(operation.get("field"), ""));
            String resolvedType = resolveBindingResultType(targetType, bindingType, operation);
            if (name.isBlank()) {
                continue;
            }
            if ("dictionaryEntry".equals(resolvedType)) {
                Map<String, Object> row = findByName(dictionaryEntries, "term", name);
                if (row != null) {
                    field = trim(Objects.toString(row.get("field"), field));
                }
            } else if ("metricDefinition".equals(resolvedType)) {
                Map<String, Object> row = findByName(metricDefinitions, "name", name);
                if (row != null) {
                    field = trim(Objects.toString(row.get("field"), field));
                }
            } else if ("dimensionDefinition".equals(resolvedType)) {
                Map<String, Object> row = findByName(dimensionDefinitions, "name", name);
                if (row != null) {
                    field = trim(Objects.toString(row.get("field"), field));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("field", field);
            result.put("formula", trim(Objects.toString(operation.get("formula"), "")));
            result.put("fieldDisplayName", resolveFieldDisplayName(field, fields));
            result.put("targetType", resolvedType);
            result.put("action", action);
            result.put("label", buildBindingTargetLabel(resolvedType, name));
            results.add(result);
        }
        return deduplicateBindingResults(results);
    }

    private String resolveFieldDisplayName(String field, List<Map<String, Object>> fields) {
        String value = trim(field);
        if (value.isBlank()) {
            return "";
        }
        for (Map<String, Object> item : fields) {
            String columnName = trim(Objects.toString(item.get("columnName"), ""));
            String sourceFieldName = trim(Objects.toString(item.get("sourceFieldName"), ""));
            String displayName = trim(Objects.toString(item.get("displayName"), ""));
            if (!value.equalsIgnoreCase(columnName)
                    && !value.equalsIgnoreCase(sourceFieldName)
                    && !value.equalsIgnoreCase(displayName)) {
                continue;
            }
            if (!displayName.isBlank() && !displayName.matches("(?i)^col_\\d+$")) {
                return displayName;
            }
            if (!sourceFieldName.isBlank() && !sourceFieldName.matches("(?i)^col_\\d+$")) {
                return sourceFieldName;
            }
            if (!columnName.isBlank()) {
                return columnName;
            }
        }
        return value;
    }

    private String buildPatchMessage(String intent, String modelName, int bindingCount, int operationCount) {
        if ("BIND_FIELDS".equals(intent)) {
            return "已按对话指令修正业务模型「" + modelName + "」的字段绑定，共更新 " + bindingCount + " 项";
        }
        return "已按对话指令更新业务模型「" + modelName + "」，共执行 " + operationCount + " 项修改";
    }

    private String resolveBindingResultType(String targetType, String bindingType, Map<String, Object> operation) {
        if ("dictionaryEntry".equals(targetType) || "metricDefinition".equals(targetType) || "dimensionDefinition".equals(targetType)) {
            return targetType;
        }
        String normalizedBindingType = trim(bindingType);
        if (!normalizedBindingType.isBlank() && !"AUTO".equalsIgnoreCase(normalizedBindingType)) {
            return normalizedBindingType;
        }
        if (operation.containsKey("term")) {
            return "dictionaryEntry";
        }
        if (operation.containsKey("formula") || operation.containsKey("aggregation")) {
            return "metricDefinition";
        }
        return "metricDefinition";
    }

    private String resolveFieldBindingOperationType(Map<String, Object> operation,
                                                    List<Map<String, Object>> existingDictionaryEntries,
                                                    List<Map<String, Object>> existingMetricDefinitions,
                                                    List<Map<String, Object>> existingDimensionDefinitions) {
        String bindingType = trim(Objects.toString(operation.get("bindingType"), ""));
        if (!bindingType.isBlank() && !"AUTO".equalsIgnoreCase(bindingType)) {
            return bindingType;
        }
        String name = resolveBindingName(operation);
        if (findByName(existingDictionaryEntries, "term", name) != null) {
            return "dictionaryEntry";
        }
        if (findByName(existingMetricDefinitions, "name", name) != null) {
            return "metricDefinition";
        }
        if (findByName(existingDimensionDefinitions, "name", name) != null) {
            return "dimensionDefinition";
        }
        if (operation.containsKey("term")) {
            return "dictionaryEntry";
        }
        return "metricDefinition";
    }

    private String resolveBindingName(Map<String, Object> operation) {
        String name = trim(Objects.toString(operation.get("name"), ""));
        if (!name.isBlank()) {
            return name;
        }
        return trim(Objects.toString(operation.get("term"), ""));
    }

    private String buildBindingTargetLabel(String targetType, String name) {
        return switch (targetType) {
            case "dictionaryEntry" -> "业务字典：" + name;
            case "dimensionDefinition" -> "业务维度：" + name;
            default -> "业务公式：" + name;
        };
    }

    private boolean looksLikeExplicitDictionaryMutation(String question) {
        return containsAny(question,
                "新增业务字典", "增加业务字典", "添加业务字典", "创建业务字典",
                "新增字典", "增加字典", "添加字典", "创建字典",
                "新增词典", "新增同义词", "新增术语");
    }

    private boolean looksLikeExplicitFormulaMutation(String question) {
        return containsAny(question,
                "新增业务公式", "增加业务公式", "添加业务公式", "创建业务公式",
                "新增指标公式", "增加指标公式", "添加指标公式", "创建指标公式",
                "新增公式", "增加公式", "添加公式", "创建公式");
    }

    private boolean looksLikeExplicitFieldBindingMutation(String question) {
        return containsAny(question,
                "字段绑定", "绑定字段", "字段修正", "改绑", "重新绑定",
                "绑定到", "绑定为", "绑定至",
                "映射到", "映射为", "映射至",
                "对应到", "对应为", "对应至");
    }

    private Map<String, Object> findByName(List<Map<String, Object>> entries, String keyName, String name) {
        String normalizedName = normalize(name);
        if (normalizedName.isBlank()) {
            return null;
        }
        for (Map<String, Object> entry : entries) {
            if (normalizedName.equals(normalize(Objects.toString(entry.get(keyName), "")))) {
                return entry;
            }
        }
        return null;
    }

    private List<Map<String, Object>> deduplicateBindingResults(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String key = normalize(Objects.toString(entry.get("targetType"), ""))
                    + "@@"
                    + normalize(Objects.toString(entry.get("name"), ""));
            if (!key.endsWith("@@")) {
                deduplicated.put(key, entry);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private String normalizeSynonyms(Object rawValue) {
        if (rawValue == null) {
            return "";
        }
        if (rawValue instanceof List<?> list) {
            Set<String> items = new LinkedHashSet<>();
            for (Object item : list) {
                String value = trim(Objects.toString(item, ""));
                if (!value.isBlank()) {
                    items.add(value);
                }
            }
            return String.join(",", items);
        }
        String text = trim(Objects.toString(rawValue, ""));
        if (text.isBlank()) {
            return "";
        }
        Set<String> items = new LinkedHashSet<>();
        for (String token : text.split("[,，;；、\\s]+")) {
            String value = trim(token);
            if (!value.isBlank()) {
                items.add(value);
            }
        }
        return String.join(",", items);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = trim(String.valueOf(value));
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stripModelSuffix(String text) {
        String value = trim(text);
        if (value.endsWith("模型")) {
            value = value.substring(0, value.length() - 2);
        }
        return value.replace("业务", "").replace("分析", "");
    }

    private String normalize(String text) {
        String value = trim(text).toLowerCase(Locale.ROOT);
        value = value.replaceAll("[\\s，。；、：:,.!?！？“”\"'（）()【】\\[\\]{}<>《》·`~|\\\\/+=-]", "");
        return value;
    }

    private String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private record ScoredModel(Map<String, Object> model, int score) {
    }
}
