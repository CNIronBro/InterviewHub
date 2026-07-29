package com.ironbro.interviewhub.interview.application.rule.review.node;

import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewContext;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewRules;
import com.ironbro.interviewhub.interview.config.InterviewSecondReviewRuleConfiguration;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;

@LiteflowComponent("lowConfidenceJudge")
@RequiredArgsConstructor
public class LowConfidenceJudgeNode extends NodeComponent {
    private final InterviewSecondReviewRuleConfiguration configuration;
    @Override public void process() {
        InterviewSecondReviewRules.judgeLowConfidence(
                getContextBean(InterviewSecondReviewContext.class), configuration);
    }
}
