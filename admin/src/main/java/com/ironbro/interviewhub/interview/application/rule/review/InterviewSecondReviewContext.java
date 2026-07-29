package com.ironbro.interviewhub.interview.application.rule.review;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InterviewSecondReviewContext {

    private Integer firstScore;
    private List<Map<String, Object>> anchorJudgments;
    private Double confidence;
    private Integer rubricVersion;
    private boolean userRequestedReview;
    private String chainId;
    private String ruleVersion;
    private boolean terminated;
    private InterviewSecondReviewDecision decision;

    public void terminate(InterviewSecondReviewAction action, String reasonCode) {
        if (terminated) {
            return;
        }
        decision = InterviewSecondReviewDecision.of(action, reasonCode, ruleVersion, false);
        terminated = true;
    }

    public void finalizeDecision(boolean fallback) {
        if (decision == null || decision.getReviewAction() == null) {
            decision = InterviewSecondReviewDecision.of(
                    InterviewSecondReviewAction.DIRECT_ACCEPT,
                    "NO_REVIEW_TRIGGER",
                    ruleVersion,
                    fallback);
        } else {
            decision.setRuleVersion(ruleVersion);
            decision.setFallback(fallback || decision.isFallback());
        }
    }
}
