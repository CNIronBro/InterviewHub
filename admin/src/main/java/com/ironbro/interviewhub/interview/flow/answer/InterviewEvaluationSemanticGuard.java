package com.ironbro.interviewhub.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prevents scorer protocol/input diagnostics from leaking into candidate feedback.
 * The scorer's free-form missing points are display data only and must remain grounded
 * in the immutable question specification.
 */
final class InterviewEvaluationSemanticGuard {

    private static final List<String> INTERNAL_DIAGNOSTIC_MARKERS = List.of(
            "未提供需要评分",
            "未提供题目和答案",
            "请提供技术面试题目",
            "缺少评分所需",
            "无法完成维度评估",
            "无法进行维度评估",
            "无法完成评分",
            "无法评分",
            "missing_points",
            "targetmissingpoints",
            "targetanchorids"
    );

    private InterviewEvaluationSemanticGuard() {
    }

    static Map<String, Object> normalize(
            Map<String, Object> source,
            String answerContent,
            String questionSpecJson) {
        if (source == null || source.isEmpty() || StrUtil.isBlank(answerContent)) {
            return source;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(source);
        boolean invalidMissingPoints = containsInternalDiagnostic(normalized.get("missing_points"));
        boolean invalidFeedback = containsInternalDiagnostic(normalized.get("feedback"))
                || isMeaninglessFeedback(normalized.get("feedback"));
        if (!invalidMissingPoints && !invalidFeedback) {
            return normalized;
        }

        List<String> knowledgePoints = extractGroundedKnowledgePoints(questionSpecJson);
        if (knowledgePoints.isEmpty()) {
            knowledgePoints = List.of("题目要求的核心概念、处理流程和工程权衡");
        }

        if (invalidMissingPoints) {
            List<String> missingPoints = knowledgePoints.stream()
                    .map(point -> "未体现：" + point)
                    .toList();
            normalized.put("missing_points", missingPoints);
        }
        if (invalidFeedback) {
            normalized.put("feedback", buildMeaningfulFeedback(normalized, knowledgePoints));
        }
        return normalized;
    }

    private static boolean containsInternalDiagnostic(Object value) {
        if (value == null) {
            return false;
        }
        String normalized = JSON.toJSONString(value).toLowerCase();
        return INTERNAL_DIAGNOSTIC_MARKERS.stream()
                .map(String::toLowerCase)
                .anyMatch(normalized::contains);
    }

    private static boolean isMeaninglessFeedback(Object value) {
        String normalized = stringValue(value).toLowerCase();
        return StrUtil.isBlank(normalized)
                || "无".equals(normalized)
                || "暂无".equals(normalized)
                || "无反馈".equals(normalized)
                || "null".equals(normalized)
                || "none".equals(normalized)
                || "n/a".equals(normalized)
                || "-".equals(normalized);
    }

    private static String buildMeaningfulFeedback(
            Map<String, Object> evaluation,
            List<String> knowledgePoints) {
        List<Map<String, Object>> anchors = anchorList(evaluation.get("anchors"));
        List<String> positiveEvidence = evidenceByStatus(anchors, "met", "partial");
        List<String> contradictedEvidence = evidenceByStatus(anchors, "contradicted");
        List<String> missingPoints = stringList(evaluation.get("missing_points"));

        boolean allMet = !anchors.isEmpty()
                && anchors.stream().allMatch(anchor -> "met".equalsIgnoreCase(stringValue(anchor.get("status"))));
        if (allMet) {
            List<String> strengths = positiveEvidence.isEmpty() ? knowledgePoints : positiveEvidence;
            return clip(
                    "回答已覆盖本题要求的核心知识，具体表现为："
                            + String.join("；", strengths.stream().limit(3).toList()),
                    300
            );
        }
        if (!contradictedEvidence.isEmpty()) {
            return clip(
                    "回答中存在需要纠正的技术认识："
                            + String.join("；", contradictedEvidence.stream().limit(2).toList())
                            + "。建议对照本题核心要求重新梳理正确机制及适用边界。",
                    300
            );
        }
        if (!positiveEvidence.isEmpty()) {
            List<String> gaps = missingPoints.isEmpty() ? knowledgePoints : missingPoints;
            return clip(
                    "回答已体现部分相关知识："
                            + String.join("；", positiveEvidence.stream().limit(2).toList())
                            + "。仍需补充："
                            + String.join("；", gaps.stream().limit(3).toList()),
                    300
            );
        }
        List<String> gaps = missingPoints.isEmpty() ? knowledgePoints : missingPoints;
        return clip(
                "回答没有体现本题要求的相关技术知识，建议重点补充："
                        + String.join("；", gaps.stream().limit(4).toList()),
                300
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> anchorList(Object value) {
        if (!(value instanceof List<?> rawAnchors)) {
            return List.of();
        }
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (Object rawAnchor : rawAnchors) {
            if (rawAnchor instanceof Map<?, ?> anchor) {
                Map<String, Object> copied = new LinkedHashMap<>();
                anchor.forEach((key, entryValue) -> copied.put(String.valueOf(key), entryValue));
                anchors.add(copied);
            }
        }
        return anchors;
    }

    private static List<String> evidenceByStatus(
            List<Map<String, Object>> anchors,
            String... acceptedStatuses) {
        Set<String> statuses = Set.of(acceptedStatuses);
        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> anchor : anchors) {
            String status = stringValue(anchor.get("status")).toLowerCase();
            String detail = stringValue(anchor.get("evidence"));
            if (statuses.contains(status)
                    && StrUtil.isNotBlank(detail)
                    && !"无相关表述".equals(detail)) {
                evidence.add(detail);
            }
        }
        return evidence;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(InterviewEvaluationSemanticGuard::stringValue)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractGroundedKnowledgePoints(String questionSpecJson) {
        if (StrUtil.isBlank(questionSpecJson)) {
            return List.of();
        }
        try {
            Map<String, Object> spec = JSON.parseObject(questionSpecJson, Map.class);
            Object rawAnchors = spec == null ? null : spec.get("anchors");
            if (!(rawAnchors instanceof List<?> anchors)) {
                return List.of();
            }

            Set<String> points = new LinkedHashSet<>();
            collectStatements(anchors, points, true);
            if (points.size() < 4) {
                collectStatements(anchors, points, false);
            }
            return new ArrayList<>(points).stream().limit(4).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void collectStatements(List<?> anchors, Set<String> points, boolean coreOnly) {
        for (Object rawAnchor : anchors) {
            if (!(rawAnchor instanceof Map<?, ?> anchor)) {
                continue;
            }
            boolean core = Boolean.parseBoolean(stringValue(anchor.get("core")));
            if (coreOnly && !core) {
                continue;
            }
            Object rawStatements = anchor.get("acceptableStatements");
            if (!(rawStatements instanceof List<?> statements)) {
                continue;
            }
            for (Object statement : statements) {
                String point = stringValue(statement);
                if (StrUtil.isNotBlank(point)) {
                    points.add(point);
                }
                if (points.size() >= 4) {
                    return;
                }
            }
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
