package com.ironbro.interviewhub.interview.application.rule.aggregation;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.config.InterviewScoreAggregationConfiguration;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewScoreAggregationService {

    @Resource
    private final FlowExecutor flowExecutor;
    private final InterviewScoreAggregationConfiguration configuration;

    public InterviewScoreAggregationDecision decide(InterviewScoreAggregationContext context) {
        if (context == null) {
            return InterviewScoreAggregationDecision.fallback(
                    "AGGREGATION_CONTEXT_MISSING", configuration.getRuleVersion());
        }

        hydrateContext(context);
        if (!Boolean.TRUE.equals(configuration.getEnable())) {
            return fallbackDecision(context, "AGGREGATION_DISABLED");
        }

        try {
            LiteflowResponse response = executeChain(resolveDefaultChainId(), context);
            if (response == null || !response.isSuccess()) {
                Throwable cause = response == null ? null : response.getCause();
                throw new IllegalStateException("LiteFlow score aggregation failed", cause);
            }
            context.finalizeDecision(false);
            return context.getDecision();
        } catch (Exception ex) {
            if (Boolean.TRUE.equals(configuration.getFailOpen())) {
                log.warn("LiteFlow score aggregation failed, returning fallback decision, chainId={}",
                        resolveDefaultChainId(), ex);
                return fallbackDecision(context, "AGGREGATION_ENGINE_FALLBACK");
            }
            throw new IllegalStateException("LiteFlow score aggregation failed", ex);
        }
    }

    LiteflowResponse executeChain(
            String chainId, InterviewScoreAggregationContext context) {
        return flowExecutor.execute2Resp(chainId, null, context);
    }

    private void hydrateContext(InterviewScoreAggregationContext context) {
        context.setRuleVersion(configuration.getRuleVersion());
        context.setTerminated(false);
        context.setDecision(new InterviewScoreAggregationDecision());
    }

    private InterviewScoreAggregationDecision fallbackDecision(
            InterviewScoreAggregationContext context, String reasonCode) {
        InterviewScoreAggregationDecision decision =
                InterviewScoreAggregationDecision.fallback(reasonCode, configuration.getRuleVersion());
        context.setDecision(decision);
        context.setTerminated(true);
        return decision;
    }

    private String resolveDefaultChainId() {
        return StrUtil.isNotBlank(configuration.getDefaultChainId())
                ? configuration.getDefaultChainId()
                : "score_aggregation_chain";
    }
}
