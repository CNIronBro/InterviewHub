package com.ironbro.interviewhub.interview.application.rule;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InterviewFollowUpRuleContext {

    private String sessionId;
    private String requestId;
    private String questionNumber;
    private String interviewType;
    private boolean followUpQuestion;
    private int followUpCount;
    private int maxFollowUp;
    private int resolvedMaxFollowUp;
    private Integer score;
    private int lowScoreThreshold;
    private int highQualityThreshold;
    private List<String> missingPoints;
    private List<Map<String, Object>> anchorJudgments;
    private String questionSpecJson;
    private List<String> targetAnchorIds;
    private List<String> targetMissingPoints;
    private boolean interviewCompleted;
    private String chainId;
    private String ruleVersion;
    private boolean terminated;
    private InterviewFollowUpRuleDecision decision;

    public void markNoFollowUp(String reasonCode, String reasonText) {
        ensureDecision();
        decision.setNeedFollowUp(false);
        decision.setReasonCode(reasonCode);
        decision.setReasonText(reasonText);
        terminated = true;
    }

    public void markNeedFollowUp(String reasonCode, String reasonText) {
        ensureDecision();
        if (!decision.isNeedFollowUp()) {
            decision.setNeedFollowUp(true);
            decision.setReasonCode(reasonCode);
            decision.setReasonText(reasonText);
        }
    }

    public void finalizeDecision(boolean fallback) {
        ensureDecision();
        if (decision.getReasonCode() == null || decision.getReasonCode().isBlank()) {
            decision.setReasonCode("NO_RULE_TRIGGER");
            decision.setReasonText("no follow-up trigger matched");
            decision.setNeedFollowUp(false);
        }
        decision.setResolvedMaxFollowUp(resolvedMaxFollowUp > 0 ? resolvedMaxFollowUp : Math.max(maxFollowUp, 1));
        decision.setChainId(chainId);
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(fallback);
        decision.setTargetAnchorIds(targetAnchorIds == null ? List.of() : List.copyOf(targetAnchorIds));
        decision.setTargetMissingPoints(targetMissingPoints == null ? List.of() : List.copyOf(targetMissingPoints));
    }

    private void ensureDecision() {
        if (decision == null) decision = new InterviewFollowUpRuleDecision();
    }
}
