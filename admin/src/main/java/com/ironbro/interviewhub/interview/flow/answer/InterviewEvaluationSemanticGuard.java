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
        if (!containsInternalDiagnostic(normalized.get("missing_points"))
                && !containsInternalDiagnostic(normalized.get("feedback"))) {
            return normalized;
        }

        List<String> knowledgePoints = extractGroundedKnowledgePoints(questionSpecJson);
        if (knowledgePoints.isEmpty()) {
            knowledgePoints = List.of("题目要求的核心概念、处理流程和工程权衡");
        }

        List<String> missingPoints = knowledgePoints.stream()
                .map(point -> "未体现：" + point)
                .toList();
        normalized.put("missing_points", missingPoints);
        normalized.put(
                "feedback",
                "回答没有体现本题要求的相关技术知识，建议重点补充："
                        + String.join("；", knowledgePoints)
        );
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
