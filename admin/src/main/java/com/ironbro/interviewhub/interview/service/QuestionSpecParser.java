package com.ironbro.interviewhub.interview.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.service.model.QuestionAnchorNormalizationResult;
import com.ironbro.interviewhub.interview.service.model.QuestionSpecParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses both legacy string questions and QuestionSpec object questions.
 */
@Component
@RequiredArgsConstructor
public class QuestionSpecParser {

    public static final int CURRENT_RUBRIC_VERSION = 1;

    private final QuestionSpecAnchorNormalizer anchorNormalizer;

    public QuestionSpecParseResult parseQuestions(Object questionsRaw) {
        if (!(questionsRaw instanceof List<?> rawQuestions) || rawQuestions.isEmpty()) {
            return emptyResult();
        }

        List<String> questions = new ArrayList<>();
        Map<String, String> anchorsByQuestionNumber = new LinkedHashMap<>();
        Map<String, String> specsByQuestionNumber = new LinkedHashMap<>();
        Integer commonVersion = null;

        for (Object rawQuestion : rawQuestions) {
            Map<String, Object> question = toQuestionObject(rawQuestion);
            if (question == null) {
                continue;
            }
            QuestionAnchorNormalizationResult normalized =
                    anchorNormalizer.normalizeAndValidateAnchors(question);
            String questionNumber = String.valueOf(questions.size() + 1);
            questions.add(normalized.getContent());
            anchorsByQuestionNumber.put(
                    questionNumber, JSON.toJSONString(normalized.getAnchors()));
            specsByQuestionNumber.put(
                    questionNumber, buildNormalizedSpec(question, normalized));
            commonVersion = mergeVersion(commonVersion, normalized.getRubricVersion());
        }

        return QuestionSpecParseResult.builder()
                .questions(questions)
                .anchorsByQuestionNumber(anchorsByQuestionNumber)
                .specsByQuestionNumber(specsByQuestionNumber)
                .rubricVersion(commonVersion == null
                        ? QuestionSpecAnchorNormalizer.LEGACY_RUBRIC_VERSION
                        : commonVersion)
                .build();
    }

    private String buildNormalizedSpec(
            Map<String, Object> rawQuestion,
            QuestionAnchorNormalizationResult normalized) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("content", normalized.getContent());
        spec.put("anchors", normalized.getAnchors());
        spec.put("commonMistakes", stringList(rawQuestion.get("commonMistakes")));
        spec.put("followUpStrategy", stringValue(rawQuestion.get("followUpStrategy")));
        spec.put("rubricVersion", normalized.getRubricVersion());
        return JSON.toJSONString(spec);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(this::stringValue)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private Map<String, Object> toQuestionObject(Object rawQuestion) {
        if (rawQuestion instanceof String content) {
            if (StrUtil.isBlank(content)) {
                return null;
            }
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("content", content.trim());
            legacy.put("rubricVersion", QuestionSpecAnchorNormalizer.LEGACY_RUBRIC_VERSION);
            return legacy;
        }
        if (rawQuestion instanceof Map<?, ?> rawMap) {
            Map<String, Object> question = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> question.put(String.valueOf(key), value));
            question.putIfAbsent("rubricVersion", CURRENT_RUBRIC_VERSION);
            return question;
        }
        return null;
    }

    private Integer mergeVersion(Integer current, Integer next) {
        if (current == null) {
            return next;
        }
        return current.equals(next)
                ? current
                : QuestionSpecAnchorNormalizer.LEGACY_RUBRIC_VERSION;
    }

    private QuestionSpecParseResult emptyResult() {
        return QuestionSpecParseResult.builder()
                .questions(List.of())
                .anchorsByQuestionNumber(Map.of())
                .specsByQuestionNumber(Map.of())
                .rubricVersion(QuestionSpecAnchorNormalizer.LEGACY_RUBRIC_VERSION)
                .build();
    }
}
