package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * A follow-up is a focused verification of something the candidate already attempted.
 * When every unresolved core anchor is missing, there is no technical evidence to deepen.
 */
@LiteflowComponent("substantiveEvidenceGuard")
public class SubstantiveEvidenceGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;

        boolean hasCoreGap = context.getTargetAnchorIds() != null
                && !context.getTargetAnchorIds().isEmpty();
        if (hasCoreGap && !context.isSubstantiveCoreEvidence()) {
            context.markNoFollowUp(
                    "NO_SUBSTANTIVE_EVIDENCE",
                    "当前回答没有可继续深挖的核心技术证据"
            );
        }
    }
}
