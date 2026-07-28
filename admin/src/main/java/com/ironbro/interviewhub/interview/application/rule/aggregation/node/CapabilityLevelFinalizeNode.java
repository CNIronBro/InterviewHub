package com.ironbro.interviewhub.interview.application.rule.aggregation.node;

import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.config.InterviewScoreAggregationConfiguration;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;

import java.util.List;

@LiteflowComponent("capabilityLevelFinalize")
@RequiredArgsConstructor
public class CapabilityLevelFinalizeNode extends NodeComponent {

    private final InterviewScoreAggregationConfiguration configuration;

    @Override
    public void process() {
        InterviewScoreAggregationContext context =
                getContextBean(InterviewScoreAggregationContext.class);
        if (context.isTerminated() || context.getDecision().getRuleScore() == null) {
            context.finalizeDecision(context.getDecision() != null
                    && context.getDecision().isFallback());
            return;
        }

        int score = context.getDecision().getRuleScore();
        if (score >= configuration.getExcellentThreshold()) {
            context.getDecision().setCapabilityLevel("EXCELLENT");
        } else if (score >= configuration.getQualifiedThreshold()) {
            context.getDecision().setCapabilityLevel("QUALIFIED");
        } else {
            context.getDecision().setCapabilityLevel("NEEDS_IMPROVEMENT");
        }

        List<String> gaps = context.getAnchors().stream()
                .filter(anchor -> !"met".equals(anchor.get("status")))
                .map(anchor -> anchor.get("anchorId").toString())
                .toList();
        context.getDecision().setGaps(gaps);
        context.finalizeDecision(false);
    }
}
