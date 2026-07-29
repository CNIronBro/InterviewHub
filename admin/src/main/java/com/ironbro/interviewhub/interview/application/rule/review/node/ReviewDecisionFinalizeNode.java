package com.ironbro.interviewhub.interview.application.rule.review.node;

import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("reviewDecisionFinalize")
public class ReviewDecisionFinalizeNode extends NodeComponent {
    @Override public void process() {
        getContextBean(InterviewSecondReviewContext.class).finalizeDecision(false);
    }
}
