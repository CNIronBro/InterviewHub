package com.ironbro.interviewhub.interview.application.rule.aggregation.node;

import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.config.InterviewScoreAggregationConfiguration;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@LiteflowComponent("criticalErrorCap")
@RequiredArgsConstructor
public class CriticalErrorCapNode extends NodeComponent {

    private final InterviewScoreAggregationConfiguration configuration;

    @Override
    public void process() {
        InterviewScoreAggregationContext context =
                getContextBean(InterviewScoreAggregationContext.class);
        if (context.isTerminated() || context.getDecision().getRuleScore() == null) {
            return;
        }

        boolean contradicted = context.getAnchors().stream().anyMatch(this::isCriticalContradiction);
        if (contradicted) {
            int cap = Math.max(0, Math.min(configuration.getCriticalErrorCap(), 100));
            context.getDecision().setRuleScore(
                    Math.min(context.getDecision().getRuleScore(), cap));
            context.getDecision().setReasonCode("CRITICAL_ERROR_CAPPED");
        }
    }

    private boolean isCriticalContradiction(Map<String, Object> anchor) {
        return configuration.getCriticalAnchorId().equals(anchor.get("anchorId"))
                && "contradicted".equals(anchor.get("status"));
    }
}
