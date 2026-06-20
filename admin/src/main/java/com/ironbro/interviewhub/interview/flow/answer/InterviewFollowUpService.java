package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.shared.InterviewAiInvoker;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 追问调度服务：低分追问、缺失知识点追问、AI 建议追问三种策略
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpService {

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;

    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId, String requestId, String currentQuestionNumber,
            String currentQuestion, String answerContent, String fallbackFollowUpQuestion,
            Integer currentFollowUpCount, Integer maxFollowUp) {

        int safeFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 2 : maxFollowUp;
        if (safeFollowUpCount >= safeMaxFollowUp) return FollowUpQuestionResult.empty();

        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) return FollowUpQuestionResult.empty();

        // 优先调追问工作流，失败则回退到评分器建议
        String generatedQuestion = null;
        try {
            AgentPropertiesDO agent = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("AGENT_USER_INPUT", answerContent);
            params.put("mode", "FOLLOW_UP");
            params.put("follow_up_count", safeFollowUpCount);
            params.put("max_follow_up", safeMaxFollowUp);
            params.put("question", currentQuestion);
            String response = interviewAiInvoker.callAiSyncWithParameters(sessionId, agent, params);
            Map<String, Object> result = interviewResponseParser.parseEvaluationResult(response);
            if (result != null) {
                generatedQuestion = interviewResponseParser.asString(result.get("ask_to_user"));
            }
        } catch (Exception ex) {
            log.warn("追问工作流调用失败, sessionId={}", sessionId, ex);
        }

        String questionContent = StrUtil.isNotBlank(generatedQuestion) ? generatedQuestion : fallbackFollowUpQuestion;
        if (StrUtil.isBlank(questionContent)) return FollowUpQuestionResult.empty();

        return new FollowUpQuestionResult(questionNumber, questionContent, safeFollowUpCount + 1);
    }

    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) return null;
        int idx = questionNumber.indexOf("-F");
        return idx > 0 ? questionNumber.substring(0, idx) : questionNumber.trim();
    }

    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) return null;
        return mainQuestionNumber + "-F" + followUpCount;
    }

    @Getter
    public static final class FollowUpQuestionResult {
        private final String questionNumber;
        private final String questionContent;
        private final Integer followUpCount;

        private FollowUpQuestionResult(String questionNumber, String questionContent, Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() { return new FollowUpQuestionResult(null, null, 0); }

        public boolean hasQuestion() { return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent); }
    }
}