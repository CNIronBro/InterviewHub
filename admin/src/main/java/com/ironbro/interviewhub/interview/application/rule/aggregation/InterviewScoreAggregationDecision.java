package com.ironbro.interviewhub.interview.application.rule.aggregation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InterviewScoreAggregationDecision {

    private Integer ruleScore;
    private String capabilityLevel;
    private List<String> gaps = new ArrayList<>();
    private String reasonCode;
    private String ruleVersion;
    private boolean fallback;

    public static InterviewScoreAggregationDecision fallback(
            String reasonCode, String ruleVersion) {
        InterviewScoreAggregationDecision decision = new InterviewScoreAggregationDecision();
        decision.setReasonCode(reasonCode);
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(true);
        return decision;
    }
}
