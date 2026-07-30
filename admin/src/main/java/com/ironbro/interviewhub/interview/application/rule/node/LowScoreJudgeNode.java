package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("lowScoreJudge")
public class LowScoreJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        Integer score = context.getScore();
        if (score != null && score < context.getLowScoreThreshold()) {
            context.markNeedFollowUp(
                    "LOW_SCORE_CLARIFICATION",
                    "低分主问题允许一次澄清: " + score + " < " + context.getLowScoreThreshold()
            );
        }
    }
}
