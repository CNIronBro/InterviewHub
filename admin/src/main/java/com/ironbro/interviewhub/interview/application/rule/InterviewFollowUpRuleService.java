package com.ironbro.interviewhub.interview.application.rule;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.config.InterviewRuleEngineConfiguration;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpRuleService {

    @Resource
    private final FlowExecutor flowExecutor;
    private final InterviewRuleEngineConfiguration ruleConfiguration;

    public InterviewFollowUpRuleDecision decide(InterviewFollowUpRuleContext context) {
        if (context == null) {
            return InterviewFollowUpRuleDecision.noFollowUp(
                    Math.max(resolveDefaultMaxFollowUp(), 1),
                    "RULE_CONTEXT_MISSING", "规则上下文缺失",
                    resolveDefaultChainId(), ruleConfiguration.getRuleVersion(), true);
        }

        hydrateContext(context);
        if (!Boolean.TRUE.equals(ruleConfiguration.getEnable())) {
            return fallbackDecision(context, "RULE_ENGINE_DISABLED", "规则引擎已禁用");
        }

        try {
            LiteflowResponse response = flowExecutor.execute2Resp(context.getChainId(), null, context);
            if (response == null || !response.isSuccess()) {
                throw new IllegalStateException("LiteFlow 执行失败");
            }
            context.finalizeDecision(false);
            return context.getDecision();
        } catch (Exception ex) {
            if (Boolean.TRUE.equals(ruleConfiguration.getFailOpen())) {
                log.warn("LiteFlow 追问决策失败，降级到兜底策略, sessionId={}", context.getSessionId(), ex);
                return fallbackDecision(context, "RULE_ENGINE_FALLBACK", "降级到兜底追问策略");
            }
            throw new IllegalStateException("LiteFlow 追问决策失败", ex);
        }
    }

    private void hydrateContext(InterviewFollowUpRuleContext context) {
        context.setChainId(resolveDefaultChainId());
        context.setRuleVersion(ruleConfiguration.getRuleVersion());
        context.setResolvedMaxFollowUp(resolveMaxFollowUp(context.getMaxFollowUp()));
        context.setLowScoreThreshold(resolveLowScoreThreshold());
        context.setDecision(new InterviewFollowUpRuleDecision());
        context.setTerminated(false);
    }

    private InterviewFollowUpRuleDecision fallbackDecision(
            InterviewFollowUpRuleContext context, String reasonCode, String reasonText) {
        int resolvedMax = resolveMaxFollowUp(context.getMaxFollowUp());
        boolean underLimit = context.getFollowUpCount() < resolvedMax;
        boolean needFollowUp = context.isFollowUpNeededFromAi() && underLimit;
        String code = needFollowUp ? "AI_SUGGESTED" : (underLimit ? reasonCode : "FOLLOW_UP_LIMIT_REACHED");
        String text = needFollowUp ? "AI 建议追问" : (underLimit ? reasonText : "追问次数已达上限");
        InterviewFollowUpRuleDecision decision = InterviewFollowUpRuleDecision.noFollowUp(
                resolvedMax, code, text, resolveDefaultChainId(),
                ruleConfiguration.getRuleVersion(), true);
        decision.setNeedFollowUp(needFollowUp);
        return decision;
    }

    private String resolveDefaultChainId() {
        return StrUtil.isNotBlank(ruleConfiguration.getDefaultChainId())
                ? ruleConfiguration.getDefaultChainId() : "default_followup_chain";
    }

    private int resolveMaxFollowUp(int fallbackMaxFollowUp) {
        return fallbackMaxFollowUp > 0 ? fallbackMaxFollowUp : resolveDefaultMaxFollowUp();
    }

    private int resolveDefaultMaxFollowUp() {
        Integer configured = ruleConfiguration.getDefaultMaxFollowUp();
        return configured != null && configured > 0 ? configured : 2;
    }

    private int resolveLowScoreThreshold() {
        Integer defaultThreshold = ruleConfiguration.getDefaultLowScoreThreshold();
        return defaultThreshold != null && defaultThreshold >= 0 ? defaultThreshold : 60;
    }
}