package com.ironbro.interviewhub.interview.application.rule.aggregation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class InterviewScoreAggregationContext {

    private List<Map<String, Object>> anchors;
    private Integer rubricVersion;
    private String ruleVersion;
    private boolean terminated;
    private InterviewScoreAggregationDecision decision;

    public void markFallback(String reasonCode) {
        ensureDecision();
        decision.setRuleScore(null);
        decision.setCapabilityLevel(null);
        decision.setGaps(new ArrayList<>());
        decision.setReasonCode(reasonCode);
        decision.setFallback(true);
        terminated = true;
    }

    public void finalizeDecision(boolean fallback) {
        ensureDecision();
        if (decision.getReasonCode() == null || decision.getReasonCode().isBlank()) {
            decision.setReasonCode("AGGREGATION_COMPLETED");
        }
        if (decision.getGaps() == null) {
            decision.setGaps(new ArrayList<>());
        }
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(fallback || decision.isFallback());
    }

    private void ensureDecision() {
        if (decision == null) {
            decision = new InterviewScoreAggregationDecision();
        }
    }
}
