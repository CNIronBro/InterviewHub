package com.ironbro.interviewhub.interview.application.rule.review;

import lombok.Data;

@Data
public class InterviewSecondReviewDecision {

    private InterviewSecondReviewAction reviewAction;
    private String reasonCode;
    private String ruleVersion;
    private boolean fallback;

    public static InterviewSecondReviewDecision of(
            InterviewSecondReviewAction action,
            String reasonCode,
            String ruleVersion,
            boolean fallback) {
        InterviewSecondReviewDecision decision = new InterviewSecondReviewDecision();
        decision.setReviewAction(action);
        decision.setReasonCode(reasonCode);
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(fallback);
        return decision;
    }
}
