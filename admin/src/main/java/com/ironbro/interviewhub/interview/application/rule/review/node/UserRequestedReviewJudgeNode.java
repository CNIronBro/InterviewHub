package com.ironbro.interviewhub.interview.application.rule.review.node;

import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewContext;
import com.ironbro.interviewhub.interview.application.rule.review.InterviewSecondReviewRules;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("userRequestedReviewJudge")
public class UserRequestedReviewJudgeNode extends NodeComponent {
    @Override public void process() {
        InterviewSecondReviewRules.judgeUserRequested(
                getContextBean(InterviewSecondReviewContext.class));
    }
}
