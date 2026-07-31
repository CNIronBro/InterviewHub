package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardException;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardStage;
import com.ironbro.interviewhub.interview.shared.InterviewAiInvoker;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpService {

    private static final String KEY_AGENT_USER_INPUT = "AGENT_USER_INPUT";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FOLLOW_UP_COUNT = "follow_up_count";
    private static final String KEY_MAX_FOLLOW_UP = "max_follow_up";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_MAIN_QUESTION = "main_question";
    private static final String KEY_MAIN_ANSWER = "main_answer";
    private static final String KEY_CURRENT_QUESTION = "current_question";
    private static final String KEY_CURRENT_ANSWER = "current_answer";
    private static final String KEY_TARGET_ANCHOR_IDS = "target_anchor_ids";
    private static final String KEY_TARGET_MISSING_POINTS = "target_missing_points";
    private static final String KEY_FOLLOW_UP_STRATEGY = "follow_up_strategy";
    private static final String KEY_ASK_TO_USER = "ask_to_user";
    private static final String KEY_END_INTERVIEW = "end_interview";

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;

    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId,
            String requestId,
            String currentQuestionNumber,
            String mainQuestion,
            String mainAnswer,
            String currentQuestion,
            String answerContent,
            String followUpStrategy,
            String parentQuestionSpec,
            List<String> targetAnchorIds,
            List<String> targetMissingPoints,
            Integer currentFollowUpCount,
            Integer maxFollowUp) {

        // 1) 先做追问次数与输入兜底，超过上限直接停止追问。
        int safeCurrentFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 1 : maxFollowUp;
        if (safeCurrentFollowUpCount >= safeMaxFollowUp) {
            return FollowUpQuestionResult.empty();
        }

        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeCurrentFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) {
            return FollowUpQuestionResult.empty();
        }

        String generatedQuestion = null;
        try {
            // 2) 优先调用追问工作流生成更针对性的追问。
            AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING);
            generatedQuestion = invokeFollowUpWorkflow(
                    sessionId,
                    requestId,
                    mainQuestion,
                    mainAnswer,
                    currentQuestion,
                    answerContent,
                    followUpStrategy,
                    targetAnchorIds,
                    targetMissingPoints,
                    safeCurrentFollowUpCount,
                    safeMaxFollowUp,
                    agentProperties
            );
        } catch (Exception ex) {
            log.warn("Follow-up agent unavailable, fallback to deterministic targeted question, sessionId={}", sessionId, ex);
        }

        // 3) 工作流失败时只允许使用原题的追问策略兜底，不读取评分 Agent 的自由文本。
        String questionContent = StrUtil.isNotBlank(generatedQuestion)
                ? generatedQuestion
                : buildDeterministicFallback(followUpStrategy);
        if (StrUtil.isBlank(questionContent)) {
            return FollowUpQuestionResult.empty();
        }

        String questionSpecJson = buildFollowUpQuestionSpec(
                questionNumber,
                mainQuestionNumber,
                questionContent,
                parentQuestionSpec,
                targetAnchorIds,
                targetMissingPoints
        );
        return new FollowUpQuestionResult(
                questionNumber,
                questionContent,
                questionSpecJson,
                safeCurrentFollowUpCount + 1
        );
    }

    private String invokeFollowUpWorkflow(
            String sessionId,
            String requestId,
            String mainQuestion,
            String mainAnswer,
            String currentQuestion,
            String answerContent,
            String followUpStrategy,
            List<String> targetAnchorIds,
            List<String> targetMissingPoints,
            int currentFollowUpCount,
            int maxFollowUp,
            AgentPropertiesDO agentProperties) {

        if (agentProperties == null || StrUtil.isBlank(currentQuestion) || StrUtil.isBlank(answerContent)) {
            return null;
        }

        try {
            Map<String, Object> parameters = buildWorkflowParameters(
                    answerContent,
                    mainQuestion,
                    mainAnswer,
                    currentQuestion,
                    followUpStrategy,
                    targetAnchorIds,
                    targetMissingPoints,
                    currentFollowUpCount,
                    maxFollowUp
            );
            log.info(
                    "Follow-up workflow request, sessionId={}, requestId={}, question={}, followUpCount={}, maxFollowUp={}",
                    sessionId,
                    requestId,
                    clip(mainQuestion, 120),
                    currentFollowUpCount,
                    maxFollowUp
            );
            String workflowResponse;
            String singleFlightKey = interviewAiInvoker.buildSingleFlightKey(
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    sessionId,
                    mainQuestion,
                    mainAnswer + "|" + answerContent + "|" + JSON.toJSONString(targetAnchorIds)
            );
            workflowResponse = interviewAiInvoker.callAiSyncWithParameters(
                    sessionId,
                    agentProperties,
                    parameters,
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    singleFlightKey
            );
            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(workflowResponse);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                log.warn("Follow-up workflow returned error, sessionId={}, message={}", sessionId, workflowErrorMessage);
                return null;
            }

            Map<String, Object> workflowResult = interviewResponseParser.extractStructuredResult(
                    workflowResponse,
                    KEY_ASK_TO_USER,
                    KEY_END_INTERVIEW
            );
            if (workflowResult != null && interviewResponseParser.asBoolean(workflowResult.get(KEY_END_INTERVIEW))) {
                return null;
            }

            String askToUser = workflowResult == null ? null : interviewResponseParser.asString(workflowResult.get(KEY_ASK_TO_USER));
            if (StrUtil.isBlank(askToUser)) {
                askToUser = interviewResponseParser.extractContentFromInterviewResponse(workflowResponse);
            }
            return sanitizeFollowUpQuestion(askToUser);
        } catch (InterviewAiGuardException ex) {
            log.warn("Follow-up workflow fast-failed, sessionId={}, code={}", sessionId, ex.getErrorCode());
            return null;
        } catch (Exception ex) {
            log.warn("Failed to invoke follow-up workflow, sessionId={}", sessionId, ex);
            return null;
        }
    }

    private Map<String, Object> buildWorkflowParameters(
            String answerContent,
            String mainQuestion,
            String mainAnswer,
            String currentQuestion,
            String followUpStrategy,
            List<String> targetAnchorIds,
            List<String> targetMissingPoints,
            int currentFollowUpCount,
            int maxFollowUp) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KEY_AGENT_USER_INPUT, answerContent);
        parameters.put(KEY_MODE, "FOLLOW_UP");
        parameters.put(KEY_FOLLOW_UP_COUNT, currentFollowUpCount);
        parameters.put(KEY_MAX_FOLLOW_UP, maxFollowUp);
        // Keep legacy "question" bound to the root main question for workflows not yet re-imported.
        parameters.put(KEY_QUESTION, mainQuestion);
        parameters.put(KEY_MAIN_QUESTION, mainQuestion);
        parameters.put(KEY_MAIN_ANSWER, mainAnswer);
        parameters.put(KEY_CURRENT_QUESTION, currentQuestion);
        parameters.put(KEY_CURRENT_ANSWER, answerContent);
        parameters.put(KEY_TARGET_ANCHOR_IDS, targetAnchorIds == null ? List.of() : targetAnchorIds);
        parameters.put(KEY_TARGET_MISSING_POINTS, targetMissingPoints == null ? List.of() : targetMissingPoints);
        Map<String, Object> directive = new LinkedHashMap<>();
        directive.put("strategy", StrUtil.blankToDefault(followUpStrategy, ""));
        directive.put("targetAnchorIds", targetAnchorIds == null ? List.of() : targetAnchorIds);
        directive.put("targetMissingPoints", targetMissingPoints == null ? List.of() : targetMissingPoints);
        parameters.put(KEY_FOLLOW_UP_STRATEGY, JSON.toJSONString(directive));
        return parameters;
    }

    private String buildDeterministicFallback(String followUpStrategy) {
        if (StrUtil.isBlank(followUpStrategy)) {
            return null;
        }
        String candidate = followUpStrategy.trim();
        int explicitQuestionIndex = candidate.lastIndexOf("追问：");
        if (explicitQuestionIndex >= 0) {
            candidate = candidate.substring(explicitQuestionIndex + "追问：".length()).trim();
        } else {
            int followUpVerbIndex = candidate.lastIndexOf("追问");
            if (followUpVerbIndex >= 0) {
                candidate = candidate.substring(followUpVerbIndex + "追问".length()).trim();
            }
        }
        candidate = candidate.replaceFirst("^[：:，,。；;\\s]+", "");
        if (candidate.length() < 8) {
            return null;
        }
        return sanitizeFollowUpQuestion(candidate);
    }

    @SuppressWarnings("unchecked")
    private String buildFollowUpQuestionSpec(
            String questionNumber,
            String parentQuestionNumber,
            String questionContent,
            String parentQuestionSpec,
            List<String> targetAnchorIds,
            List<String> targetMissingPoints) {
        Map<String, Object> followUpSpec = new LinkedHashMap<>();
        followUpSpec.put("questionId", questionNumber);
        followUpSpec.put("parentQuestionId", parentQuestionNumber);
        followUpSpec.put("isFollowUp", true);
        followUpSpec.put("content", questionContent);
        followUpSpec.put("targetAnchorIds", targetAnchorIds == null ? List.of() : targetAnchorIds);
        followUpSpec.put("targetMissingPoints", targetMissingPoints == null ? List.of() : targetMissingPoints);
        followUpSpec.put("rubricVersion", 1);

        List<Map<String, Object>> selectedAnchors = new ArrayList<>();
        try {
            Map<String, Object> parentSpec = StrUtil.isBlank(parentQuestionSpec)
                    ? null
                    : JSON.parseObject(parentQuestionSpec, Map.class);
            Object rawAnchors = parentSpec == null ? null : parentSpec.get("anchors");
            if (rawAnchors instanceof List<?> anchors) {
                for (Object rawAnchor : anchors) {
                    if (!(rawAnchor instanceof Map<?, ?> anchor)) continue;
                    String anchorId = stringValue(anchor.get("name"));
                    if (StrUtil.isBlank(anchorId)) {
                        anchorId = stringValue(anchor.get("anchorId"));
                    }
                    if (targetAnchorIds == null || targetAnchorIds.isEmpty() || targetAnchorIds.contains(anchorId)) {
                        Map<String, Object> copied = new LinkedHashMap<>();
                        anchor.forEach((key, value) -> copied.put(String.valueOf(key), value));
                        copied.put("core", true);
                        copied.put("weight", selectedAnchors.isEmpty() ? 100 : 0);
                        selectedAnchors.add(copied);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to derive follow-up rubric from parent spec", ex);
        }

        if (selectedAnchors.isEmpty()) {
            Map<String, Object> fallbackAnchor = new LinkedHashMap<>();
            fallbackAnchor.put("name", "correctness");
            fallbackAnchor.put("weight", 100);
            fallbackAnchor.put("core", true);
            fallbackAnchor.put("acceptableStatements",
                    targetMissingPoints == null || targetMissingPoints.isEmpty()
                            ? List.of("针对追问题面给出相关、正确且可验证的说明")
                            : targetMissingPoints);
            selectedAnchors.add(fallbackAnchor);
        } else if (selectedAnchors.size() > 1) {
            int baseWeight = 100 / selectedAnchors.size();
            int assigned = 0;
            for (int index = 0; index < selectedAnchors.size(); index++) {
                int weight = index == selectedAnchors.size() - 1 ? 100 - assigned : baseWeight;
                selectedAnchors.get(index).put("weight", weight);
                assigned += weight;
            }
        }
        followUpSpec.put("anchors", selectedAnchors);
        followUpSpec.put("commonMistakes", List.of());
        followUpSpec.put("followUpStrategy", "");
        return JSON.toJSONString(followUpSpec);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (containsInternalProtocolText(normalized)) {
            log.warn("Rejected follow-up question containing internal protocol text: {}", clip(normalized, 120));
            return null;
        }
        if (!normalized.endsWith("?") && !normalized.endsWith("？")) {
            normalized = normalized + "？";
        }
        return clip(normalized, 100);
    }

    private boolean containsInternalProtocolText(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        return normalized.contains("missing_points")
                || normalized.contains("targetmissingpoints")
                || normalized.contains("targetanchorids")
                || normalized.contains("follow_up_strategy")
                || normalized.contains("未提供需要评分")
                || normalized.contains("未提供题目和答案")
                || normalized.contains("请提供技术面试题目")
                || normalized.contains("无法完成评分")
                || normalized.contains("无法评分")
                || normalized.contains("评分所需的核心内容");
    }

    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separatorIndex = normalized.indexOf("-F");
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) {
            return null;
        }
        return mainQuestionNumber + "-F" + followUpCount;
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @Getter
    public static final class FollowUpQuestionResult {
        private final String questionNumber;
        private final String questionContent;
        private final String questionSpecJson;
        private final Integer followUpCount;

        private FollowUpQuestionResult(
                String questionNumber,
                String questionContent,
                String questionSpecJson,
                Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.questionSpecJson = questionSpecJson;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() {
            return new FollowUpQuestionResult(null, null, null, 0);
        }

        public boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}


