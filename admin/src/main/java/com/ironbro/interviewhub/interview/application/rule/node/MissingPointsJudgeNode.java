package com.ironbro.interviewhub.interview.application.rule.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

@LiteflowComponent("missingPointsJudge")
public class MissingPointsJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;
        boolean hasMissingPoints = CollUtil.isNotEmpty(context.getMissingPoints());
        boolean hasFollowUpHint = context.isFollowUpNeededFromAi()
                && StrUtil.isNotBlank(context.getFollowUpQuestionHint());
        if (hasMissingPoints || hasFollowUpHint) {
            context.markNeedFollowUp("MISSING_POINTS", "存在缺失知识点或追问提示");
        }
    }
}
