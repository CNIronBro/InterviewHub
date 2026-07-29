package com.ironbro.interviewhub.interview.application.rule.review;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.config.InterviewSecondReviewRuleConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InterviewSecondReviewRules {

    private static final Set<String> REQUIRED_ANCHORS =
            Set.of("correctness", "completeness", "logic", "depth", "clarity");
    private static final Set<String> VALID_STATUSES =
            Set.of("met", "partial", "missing", "contradicted");

    private InterviewSecondReviewRules() {
    }

    public static void load(
            InterviewSecondReviewContext context,
            InterviewSecondReviewRuleConfiguration configuration) {
        if (context.getDecision() == null) {
            context.setDecision(new InterviewSecondReviewDecision());
        }
        if (context.getRuleVersion() == null) {
            context.setRuleVersion(configuration.getRuleVersion());
        }
    }

    public static void guardCompleteness(InterviewSecondReviewContext context) {
        if (context.isTerminated()) return;
        if (context.getFirstScore() == null
                || context.getFirstScore() < 0
                || context.getFirstScore() > 100
                || context.getRubricVersion() == null
                || context.getRubricVersion() < 0
                || context.getConfidence() == null
                || !Double.isFinite(context.getConfidence())
                || context.getConfidence() < 0D
                || context.getConfidence() > 1D
                || !completeAnchors(context.getAnchorJudgments())) {
            context.terminate(
                    InterviewSecondReviewAction.CONSERVATIVE_RESULT,
                    "REVIEW_CONTEXT_INCOMPLETE");
        }
    }

    public static void judgeBoundary(
            InterviewSecondReviewContext context,
            InterviewSecondReviewRuleConfiguration configuration) {
        if (context.isTerminated()) return;
        List<Integer> boundaries = configuration.getBoundaryScores();
        if (boundaries != null && boundaries.contains(context.getFirstScore())) {
            context.terminate(InterviewSecondReviewAction.SECOND_REVIEW, "BOUNDARY_SCORE");
        }
    }

    public static void judgeAnchorConflict(
            InterviewSecondReviewContext context,
            InterviewSecondReviewRuleConfiguration configuration) {
        if (context.isTerminated()) return;
        String criticalId = StrUtil.blankToDefault(configuration.getCriticalAnchorId(), "correctness");
        boolean conflict = context.getAnchorJudgments().stream().anyMatch(anchor ->
                criticalId.equals(string(anchor.get("anchorId")))
                        && "contradicted".equals(string(anchor.get("status"))));
        if (!conflict) return;
        InterviewSecondReviewAction action;
        try {
            action = InterviewSecondReviewAction.valueOf(
                    StrUtil.blankToDefault(
                            configuration.getAnchorConflictAction(),
                            InterviewSecondReviewAction.CONSERVATIVE_RESULT.name()));
        } catch (IllegalArgumentException ignored) {
            action = InterviewSecondReviewAction.CONSERVATIVE_RESULT;
        }
        context.terminate(action, "CRITICAL_ANCHOR_CONFLICT");
    }

    public static void judgeLowConfidence(
            InterviewSecondReviewContext context,
            InterviewSecondReviewRuleConfiguration configuration) {
        if (context.isTerminated()) return;
        double threshold = configuration.getLowConfidenceThreshold() == null
                ? 0.7D : configuration.getLowConfidenceThreshold();
        boolean low = context.getConfidence() != null && context.getConfidence() < threshold;
        if (!low) {
            String criticalId = StrUtil.blankToDefault(configuration.getCriticalAnchorId(), "correctness");
            low = context.getAnchorJudgments().stream()
                    .filter(anchor -> criticalId.equals(string(anchor.get("anchorId"))))
                    .map(anchor -> anchor.get("confidence"))
                    .anyMatch(value -> value instanceof Number number
                            && number.doubleValue() < threshold);
        }
        if (low) {
            context.terminate(InterviewSecondReviewAction.SECOND_REVIEW, "LOW_CONFIDENCE");
        }
    }

    public static void judgeUserRequested(InterviewSecondReviewContext context) {
        if (context.isTerminated()) return;
        if (context.isUserRequestedReview()) {
            context.terminate(InterviewSecondReviewAction.SECOND_REVIEW, "USER_REQUESTED_REVIEW");
        }
    }

    private static boolean completeAnchors(List<Map<String, Object>> anchors) {
        if (anchors == null || anchors.size() != REQUIRED_ANCHORS.size()) {
            return false;
        }
        Set<String> actualIds = new java.util.HashSet<>();
        for (Map<String, Object> anchor : anchors) {
            if (anchor == null) return false;
            String anchorId = string(anchor.get("anchorId"));
            String status = string(anchor.get("status"));
            String evidence = string(anchor.get("evidence"));
            Object confidence = anchor.get("confidence");
            if (!REQUIRED_ANCHORS.contains(anchorId)
                    || !actualIds.add(anchorId)
                    || !VALID_STATUSES.contains(status)
                    || StrUtil.isBlank(evidence)
                    || !(confidence instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0D
                    || number.doubleValue() > 1D) {
                return false;
            }
        }
        return actualIds.equals(REQUIRED_ANCHORS);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
