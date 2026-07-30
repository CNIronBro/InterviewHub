package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * A follow-up answer closes the current topic. It must not recursively open a new branch.
 */
@LiteflowComponent("currentFollowUpGuard")
public class CurrentFollowUpGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        if (context.isFollowUpQuestion()) {
            context.markNoFollowUp("FOLLOW_UP_ANSWERED", "当前题已是追问，不继续递归深挖");
        }
    }
}
