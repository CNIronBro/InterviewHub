package com.ironbro.interviewhub.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI 响应解析器：兼容 JSON / Markdown 代码块 / 纯文本
 */
@Component
@Slf4j
public class InterviewResponseParser {

    /** 解析评分结果，优先从 choices[0].delta.content 提取 */
    public Map<String, Object> parseEvaluationResult(String aiResponseStr) {
        String contentStr = extractContentFromInterviewResponse(aiResponseStr);
        Map<String, Object> parsed = tryParseObject(contentStr);
        return parsed != null ? parsed : tryParseObject(aiResponseStr);
    }

    /** 从 OpenAI 兼容响应中提取内容文本 */
    public String extractContentFromInterviewResponse(String aiResponse) {
        try {
            JSONObject jsonObject = JSON.parseObject(aiResponse);
            if (jsonObject == null) return aiResponse;

            if (jsonObject.containsKey("choices")) {
                JSONArray choices = jsonObject.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject first = choices.getJSONObject(0);
                    if (first != null && first.containsKey("delta")) {
                        JSONObject delta = first.getJSONObject("delta");
                        if (delta != null && delta.containsKey("content"))
                            return delta.getString("content");
                    }
                }
            }
            if (jsonObject.containsKey("content")) return jsonObject.getString("content");
            return aiResponse;
        } catch (Exception e) {
            return aiResponse;
        }
    }

    /** 从响应 Map 中解析整数分数，限制在 0-100 */
    public Integer parseScoreFromResponse(Map<String, Object> responseMap, String scoreKey) {
        if (responseMap == null) return null;
        Object scoreObj = responseMap.get(scoreKey);
        if (scoreObj == null) return null;
        Integer score;
        if (scoreObj instanceof Number n) score = (int) Math.round(n.doubleValue());
        else if (scoreObj instanceof String s) {
            try { score = (int) Math.round(Double.parseDouble(s.trim())); }
            catch (NumberFormatException e) { return null; }
        } else return null;
        return Math.max(0, Math.min(100, score));
    }

    public boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String s = value.toString().trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    public String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    public List<String> asStringList(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List<?> rawList)
            return rawList.stream().filter(Objects::nonNull).map(String::valueOf).map(String::trim)
                    .filter(StrUtil::isNotBlank).toList();
        return StrUtil.isBlank(value.toString()) ? Collections.emptyList()
                : Collections.singletonList(value.toString().trim());
    }

    private Map<String, Object> tryParseObject(String text) {
        if (StrUtil.isBlank(text)) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        try {
            JSONObject obj = JSON.parseObject(cleaned.trim());
            return obj != null ? new LinkedHashMap<>(obj) : null;
        } catch (Exception e) { return null; }
    }
}