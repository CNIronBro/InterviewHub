package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.shared.InterviewAiInvoker;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试评分服务 — 五维评分：正确性(40%)、完整性(20%)、清晰度(5%)、深度(15%)、相关性(20%)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewEvaluationService {

    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;

    public Map<String, Object> evaluateAnswer(
            String sessionId, String requestId, String questionNumber,
            String questionContent, String answerContent, AgentPropertiesDO scorerAgent) {

        String prompt = buildFiveDimensionPrompt(questionContent, answerContent);
        try {
            String aiResponse = interviewAiInvoker.callAiSync(prompt, sessionId, scorerAgent);
            Map<String, Object> result = interviewResponseParser.parseEvaluationResult(aiResponse);
            if (result == null || result.isEmpty()) return buildFallbackResult();
            return normalizeResult(result);
        } catch (Exception ex) {
            log.warn("评分调用失败, sessionId={}", sessionId, ex);
            return buildFallbackResult();
        }
    }

    private String buildFiveDimensionPrompt(String question, String answer) {
        return String.format("""
                你是严格的技术面试评分器。按五维评分体系评估，返回严格JSON。

                题目：%s
                答案：%s

                五维权重：correctness 40%%、completeness 20%%、clarity 5%%、depth 15%%、relevance 20%%

                输出格式：
                {"score":<0-100整数>,"logic_ok":<true/false>,"missing_points":["..."],"feedback":"...","follow_up_needed":<true/false>,"follow_up_question":"..."}
                """, question, answer);
    }

    private Map<String, Object> buildFallbackResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", 0);
        result.put("logic_ok", false);
        result.put("missing_points", Collections.emptyList());
        result.put("feedback", "评分服务暂不可用");
        result.put("follow_up_needed", false);
        result.put("follow_up_question", "");
        return result;
    }

    private Map<String, Object> normalizeResult(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>(raw);
        normalized.putIfAbsent("missing_points", Collections.emptyList());
        normalized.putIfAbsent("feedback", "");
        normalized.putIfAbsent("follow_up_question", "");
        normalized.putIfAbsent("logic_ok", false);
        if (!normalized.containsKey("follow_up_needed")) {
            boolean logicOk = interviewResponseParser.asBoolean(normalized.get("logic_ok"));
            normalized.put("follow_up_needed", !logicOk);
        }
        return normalized;
    }
}