package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("completedStateGuard")
public class CompletedStateGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isInterviewCompleted()) {
            context.markNoFollowUp("INTERVIEW_COMPLETED", "面试已完成");
        }
    }
}