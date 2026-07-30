package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("missingPointsJudge")
public class MissingPointsJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        if (context.getTargetAnchorIds() != null && !context.getTargetAnchorIds().isEmpty()) {
            context.markNeedFollowUp("CORE_ANCHOR_GAP", "存在尚未验证的核心评分锚点");
        }
    }
}
