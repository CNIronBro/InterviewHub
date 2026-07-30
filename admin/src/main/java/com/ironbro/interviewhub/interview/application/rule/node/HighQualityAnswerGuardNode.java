package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * A strong answer with all core anchors met must not be followed merely because more depth is possible.
 */
@LiteflowComponent("highQualityAnswerGuard")
public class HighQualityAnswerGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        Integer score = context.getScore();
        boolean noCoreGap = context.getTargetAnchorIds() == null || context.getTargetAnchorIds().isEmpty();
        if (score != null && score >= context.getHighQualityThreshold() && noCoreGap) {
            context.markNoFollowUp("HIGH_QUALITY_COMPLETE", "高质量回答已覆盖全部核心锚点");
        }
    }
}
