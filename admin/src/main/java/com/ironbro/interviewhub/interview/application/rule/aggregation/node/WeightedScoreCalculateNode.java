package com.ironbro.interviewhub.interview.application.rule.aggregation.node;

import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.config.InterviewScoreAggregationConfiguration;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@LiteflowComponent("weightedScoreCalculate")
@RequiredArgsConstructor
public class WeightedScoreCalculateNode extends NodeComponent {

    private final InterviewScoreAggregationConfiguration configuration;

    @Override
    public void process() {
        InterviewScoreAggregationContext context =
                getContextBean(InterviewScoreAggregationContext.class);
        if (context.isTerminated()) {
            return;
        }

        double weightedScore = 0D;
        int totalWeight = 0;
        for (Map<String, Object> anchor : context.getAnchors()) {
            String anchorId = anchor.get("anchorId").toString().trim();
            String status = anchor.get("status").toString().trim();
            int weight = configuration.getAnchorWeights().get(anchorId);
            double factor = configuration.getStatusFactors().get(status);
            weightedScore += weight * factor;
            totalWeight += weight;
        }
        int score = (int) Math.round(weightedScore * 100D / totalWeight);
        context.getDecision().setRuleScore(Math.max(0, Math.min(score, 100)));
    }
}
