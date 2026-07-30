package com.ironbro.interviewhub.interview.application.rule.review;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationDecision;
import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationService;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InterviewSecondReviewMergeService {

    private static final String CRITICAL_ANCHOR = "correctness";

    private final InterviewResponseParser responseParser;
    private final InterviewScoreAggregationService aggregationService;

    public InterviewSecondReviewMergeResult merge(
            Integer firstScore,
            Integer firstRuleScore,
            List<Map<String, Object>> firstAnchors,
            String firstRuleVersion,
            Map<String, Object> reviewed) {
        List<Map<String, Object>> reviewedAnchors =
                responseParser.parseAnchorResult(JSON.toJSONString(reviewed));
        Integer reviewedScore = responseParser.parseScoreFromResponse(reviewed, "score");
        Integer reviewedRuleScore = responseParser.parseScoreFromResponse(reviewed, "ruleScore");

        InterviewSecondReviewMergeResult result =
                base(firstScore, firstRuleScore, firstAnchors, firstRuleVersion);
        if (criticalConflict(firstAnchors, reviewedAnchors)) {
            result.setFinalScore(minValid(firstRuleScore, reviewedRuleScore, firstScore, reviewedScore));
            result.setFinalStrategy("CONSERVATIVE_CRITICAL_ANCHOR_CONFLICT");
            result.setNeedsReview(true);
            return result;
        }
        if (bothLackEvidence(firstAnchors, reviewedAnchors)) {
            result.setFinalStrategy("INSUFFICIENT_EVIDENCE_REQUEST_MORE_ANSWER");
            result.setHidePreciseScore(true);
            result.setNeedsReview(true);
            return result;
        }
        if (anchorsConsistent(firstAnchors, reviewedAnchors)) {
            if (Objects.equals(firstScore, reviewedScore)) {
                result.setFinalStrategy("FIRST_RESULT_ANCHORS_CONSISTENT");
                return result;
            }
            InterviewScoreAggregationContext context = new InterviewScoreAggregationContext();
            context.setAnchors(reviewedAnchors);
            context.setRubricVersion(0);
            InterviewScoreAggregationDecision decision = aggregationService.decide(context);
            if (decision.getRuleScore() != null) {
                result.setFinalScore(decision.getRuleScore());
                result.setRuleScore(decision.getRuleScore());
                result.setAnchorJudgments(reviewedAnchors);
                result.setRuleVersion(decision.getRuleVersion());
                result.setFinalStrategy("RULE_REAGGREGATED");
                return result;
            }
        }
        result.setFinalScore(minValid(firstRuleScore, reviewedRuleScore, firstScore, reviewedScore));
        result.setFinalStrategy("CONSERVATIVE_REVIEW_DIVERGENCE");
        result.setNeedsReview(true);
        return result;
    }

    public InterviewSecondReviewMergeResult conservative(
            Integer firstScore,
            Integer firstRuleScore,
            List<Map<String, Object>> firstAnchors,
            String firstRuleVersion,
            String strategy) {
        InterviewSecondReviewMergeResult result =
                base(firstScore, firstRuleScore, firstAnchors, firstRuleVersion);
        result.setFinalStrategy(StrUtil.blankToDefault(strategy, "CONSERVATIVE_RESULT"));
        result.setNeedsReview(true);
        return result;
    }

    private InterviewSecondReviewMergeResult base(
            Integer firstScore,
            Integer firstRuleScore,
            List<Map<String, Object>> firstAnchors,
            String firstRuleVersion) {
        InterviewSecondReviewMergeResult result = new InterviewSecondReviewMergeResult();
        result.setFinalScore(firstRuleScore == null ? firstScore : firstRuleScore);
        result.setRuleScore(firstRuleScore);
        result.setAnchorJudgments(firstAnchors);
        result.setRuleVersion(firstRuleVersion);
        result.setFinalStrategy("FIRST_RESULT");
        return result;
    }

    private boolean criticalConflict(
            List<Map<String, Object>> first, List<Map<String, Object>> reviewed) {
        String firstStatus = status(first, CRITICAL_ANCHOR);
        String reviewedStatus = status(reviewed, CRITICAL_ANCHOR);
        return firstStatus != null && reviewedStatus != null
                && !Objects.equals(firstStatus, reviewedStatus)
                && ("contradicted".equals(firstStatus) || "contradicted".equals(reviewedStatus));
    }

    private boolean bothLackEvidence(
            List<Map<String, Object>> first, List<Map<String, Object>> reviewed) {
        return lacksEvidence(first) && lacksEvidence(reviewed);
    }

    private boolean lacksEvidence(List<Map<String, Object>> anchors) {
        return anchors == null || anchors.isEmpty() || anchors.stream().allMatch(anchor -> {
            String status = string(anchor.get("status"));
            return "missing".equals(status) || StrUtil.isBlank(string(anchor.get("evidence")));
        });
    }

    private boolean anchorsConsistent(
            List<Map<String, Object>> first, List<Map<String, Object>> reviewed) {
        if (first == null || reviewed == null || first.size() != reviewed.size()) return false;
        return first.stream().allMatch(anchor -> {
            String id = string(anchor.get("anchorId"));
            Map<String, Object> other = reviewed.stream()
                    .filter(candidate -> Objects.equals(id, string(candidate.get("anchorId"))))
                    .findFirst().orElse(null);
            return other != null
                    && Objects.equals(string(anchor.get("status")), string(other.get("status")))
                    && Objects.equals(string(anchor.get("evidence")), string(other.get("evidence")));
        });
    }

    private String status(List<Map<String, Object>> anchors, String id) {
        if (anchors == null) return null;
        return anchors.stream()
                .filter(anchor -> Objects.equals(id, string(anchor.get("anchorId"))))
                .map(anchor -> string(anchor.get("status")))
                .findFirst().orElse(null);
    }

    private Integer minValid(Integer... scores) {
        Integer result = null;
        for (Integer score : scores) {
            if (score == null) continue;
            int safe = Math.max(0, Math.min(100, score));
            result = result == null ? safe : Math.min(result, safe);
        }
        return result == null ? 0 : result;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
