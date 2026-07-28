package com.ironbro.interviewhub.interview.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ironbro.interviewhub.interview.service.model.QuestionAnchorNormalizationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the anchor portion of a QuestionSpec without connecting it to the
 * extraction workflow. Invalid anchor sets are replaced atomically by a legacy
 * single-anchor rubric.
 */
@Slf4j
@Component
public class QuestionSpecAnchorNormalizer {

    public static final int LEGACY_RUBRIC_VERSION = 0;

    public QuestionAnchorNormalizationResult normalizeAndValidateAnchors(Object questionsRaw) {
        Map<String, Object> question = asQuestionMap(questionsRaw);
        String content = stringValue(question.get("content"));
        if (StrUtil.isBlank(content)) {
            throw new IllegalArgumentException("question content must not be blank");
        }

        Object anchorsRaw = question.get("anchors");
        List<Map<String, Object>> anchors = parseAnchors(anchorsRaw);
        String invalidReason = validateAnchors(anchors);
        if (invalidReason != null) {
            log.info("Question anchors use legacy fallback, reason={}", invalidReason);
            return legacyResult(content, invalidReason);
        }

        Integer rubricVersion = positiveVersion(question.get("rubricVersion"));
        if (rubricVersion == null) {
            return legacyResult(content, "rubricVersion is missing or invalid");
        }
        return QuestionAnchorNormalizationResult.builder()
                .content(content)
                .anchors(anchors)
                .rubricVersion(rubricVersion)
                .legacyFallback(false)
                .build();
    }

    private Map<String, Object> asQuestionMap(Object raw) {
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (raw instanceof String rawJson && StrUtil.isNotBlank(rawJson)) {
            try {
                JSONObject parsed = JSON.parseObject(rawJson);
                if (parsed != null) {
                    return new LinkedHashMap<>(parsed);
                }
            } catch (Exception ex) {
                throw new IllegalArgumentException("question must be a valid JSON object", ex);
            }
        }
        throw new IllegalArgumentException("question must be an object");
    }

    private List<Map<String, Object>> parseAnchors(Object raw) {
        Object candidate = raw;
        if (raw instanceof String rawJson) {
            try {
                candidate = JSON.parseArray(rawJson);
            } catch (Exception ex) {
                return null;
            }
        }
        if (!(candidate instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawAnchor)) {
                return null;
            }
            Map<String, Object> anchor = new LinkedHashMap<>();
            rawAnchor.forEach((key, value) -> anchor.put(String.valueOf(key), value));
            result.add(anchor);
        }
        return result;
    }

    private String validateAnchors(List<Map<String, Object>> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return "anchors are missing or malformed";
        }
        int totalWeight = 0;
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> anchor : anchors) {
            String name = stringValue(anchor.get("name"));
            Integer weight = positiveInteger(anchor.get("weight"));
            if (StrUtil.isBlank(name) || !names.add(name)) {
                return "anchor names must be non-blank and unique";
            }
            if (weight == null) {
                return "anchor weight must be a positive integer";
            }
            if (!(anchor.get("core") instanceof Boolean)) {
                return "anchor core must be boolean";
            }
            if (!validAcceptableStatements(anchor.get("acceptableStatements"))) {
                return "acceptableStatements must contain non-blank strings";
            }
            totalWeight += weight;
        }
        return totalWeight == 100 ? null : "anchor weights must total 100";
    }

    private boolean validAcceptableStatements(Object raw) {
        if (!(raw instanceof List<?> statements) || statements.isEmpty()) {
            return false;
        }
        return statements.stream().allMatch(item -> StrUtil.isNotBlank(stringValue(item)));
    }

    private QuestionAnchorNormalizationResult legacyResult(String content, String reason) {
        Map<String, Object> legacyAnchor = new LinkedHashMap<>();
        legacyAnchor.put("name", "legacy");
        legacyAnchor.put("weight", 100);
        legacyAnchor.put("core", true);
        legacyAnchor.put("acceptableStatements", List.of("按旧版题面整体判断答案正确性"));
        return QuestionAnchorNormalizationResult.builder()
                .content(content)
                .anchors(List.of(legacyAnchor))
                .rubricVersion(LEGACY_RUBRIC_VERSION)
                .legacyFallback(true)
                .fallbackReason(reason)
                .build();
    }

    private Integer positiveVersion(Object raw) {
        Integer value = nonNegativeInteger(raw);
        return value != null && value > LEGACY_RUBRIC_VERSION ? value : null;
    }

    private Integer positiveInteger(Object raw) {
        Integer value = nonNegativeInteger(raw);
        return value != null && value > 0 ? value : null;
    }

    private Integer nonNegativeInteger(Object raw) {
        if (raw instanceof Number number) {
            double doubleValue = number.doubleValue();
            int intValue = number.intValue();
            return Double.isFinite(doubleValue) && doubleValue == intValue && intValue >= 0
                    ? intValue : null;
        }
        if (raw instanceof String text && text.matches("\\d+")) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }
}
