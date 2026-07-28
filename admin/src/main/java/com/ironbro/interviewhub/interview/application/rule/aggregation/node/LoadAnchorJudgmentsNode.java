package com.ironbro.interviewhub.interview.application.rule.aggregation.node;

import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationDecision;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("loadAnchorJudgments")
public class LoadAnchorJudgmentsNode extends NodeComponent {

    @Override
    public void process() {
        InterviewScoreAggregationContext context =
                getContextBean(InterviewScoreAggregationContext.class);
        if (context.getDecision() == null) {
            context.setDecision(new InterviewScoreAggregationDecision());
        }
    }
}
