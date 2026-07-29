package com.ironbro.interviewhub.interview.application.rule.review;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.config.InterviewSecondReviewRuleConfiguration;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewSecondReviewRuleService {

    @Resource
    private final FlowExecutor flowExecutor;
    private final InterviewSecondReviewRuleConfiguration configuration;

    public InterviewSecondReviewDecision decide(InterviewSecondReviewContext context) {
        if (context == null) {
            return fallbackDecision("REVIEW_CONTEXT_MISSING");
        }
        hydrate(context);
        if (!Boolean.TRUE.equals(configuration.getEnable())) {
            return fallbackDecision("REVIEW_ENGINE_DISABLED");
        }
        try {
            LiteflowResponse response = executeChain(context.getChainId(), context);
            if (response == null || !response.isSuccess()) {
                throw new IllegalStateException(
                        "LiteFlow second review execution failed",
                        response == null ? null : response.getCause());
            }
            context.finalizeDecision(false);
            return context.getDecision();
        } catch (Exception exception) {
            if (Boolean.TRUE.equals(configuration.getFailOpen())) {
                log.warn("Second review rule chain failed, using fail-open decision, chainId={}",
                        context.getChainId(), exception);
                return fallbackDecision("REVIEW_ENGINE_FAIL_OPEN");
            }
            log.warn("Second review rule chain failed, using fail-close decision, chainId={}",
                    context.getChainId(), exception);
            return InterviewSecondReviewDecision.of(
                    InterviewSecondReviewAction.CONSERVATIVE_RESULT,
                    "REVIEW_ENGINE_FAIL_CLOSE",
                    configuration.getRuleVersion(),
                    true);
        }
    }

    LiteflowResponse executeChain(String chainId, InterviewSecondReviewContext context) {
        return flowExecutor.execute2Resp(chainId, null, context);
    }

    private void hydrate(InterviewSecondReviewContext context) {
        context.setChainId(StrUtil.blankToDefault(
                configuration.getDefaultChainId(), "second_review_chain"));
        context.setRuleVersion(StrUtil.blankToDefault(
                configuration.getRuleVersion(), "v1.0.0"));
        context.setTerminated(false);
        context.setDecision(new InterviewSecondReviewDecision());
    }

    private InterviewSecondReviewDecision fallbackDecision(String reasonCode) {
        InterviewSecondReviewAction action = Boolean.TRUE.equals(configuration.getFailOpen())
                ? InterviewSecondReviewAction.DIRECT_ACCEPT
                : InterviewSecondReviewAction.CONSERVATIVE_RESULT;
        return InterviewSecondReviewDecision.of(
                action,
                reasonCode,
                StrUtil.blankToDefault(configuration.getRuleVersion(), "v1.0.0"),
                true);
    }
}
