package com.ironbro.interviewhub.interview.application.rule.node;

import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("aiSuggestionJudge")
public class AiSuggestionJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        if (context.isFollowUpNeededFromAi()) {
            context.markNeedFollowUp("AI_SUGGESTED", "AI 建议追问");
        }
    }
}