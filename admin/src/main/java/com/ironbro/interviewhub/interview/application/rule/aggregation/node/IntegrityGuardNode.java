package com.ironbro.interviewhub.interview.application.rule.aggregation.node;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.application.rule.aggregation.InterviewScoreAggregationContext;
import com.ironbro.interviewhub.interview.config.InterviewScoreAggregationConfiguration;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@LiteflowComponent("integrityGuard")
@RequiredArgsConstructor
public class IntegrityGuardNode extends NodeComponent {

    private final InterviewScoreAggregationConfiguration configuration;

    @Override
    public void process() {
        InterviewScoreAggregationContext context =
                getContextBean(InterviewScoreAggregationContext.class);
        List<Map<String, Object>> anchors = context.getAnchors();
        Set<String> expectedIds = configuration.getAnchorWeights().keySet();
        if (anchors == null || anchors.size() != expectedIds.size() || expectedIds.isEmpty()) {
            context.markFallback("ANCHOR_JUDGMENTS_INCOMPLETE");
            return;
        }

        Set<String> actualIds = new HashSet<>();
        for (Map<String, Object> anchor : anchors) {
            if (anchor == null) {
                context.markFallback("ANCHOR_JUDGMENTS_INVALID");
                return;
            }
            String anchorId = stringValue(anchor.get("anchorId"));
            String status = stringValue(anchor.get("status"));
            if (!expectedIds.contains(anchorId)
                    || !actualIds.add(anchorId)
                    || !configuration.getStatusFactors().containsKey(status)
                    || StrUtil.isBlank(stringValue(anchor.get("evidence")))
                    || !validConfidence(anchor.get("confidence"))) {
                context.markFallback("ANCHOR_JUDGMENTS_INVALID");
                return;
            }
        }

        if (!actualIds.equals(expectedIds)
                || configuration.getAnchorWeights().values().stream()
                        .anyMatch(weight -> weight == null || weight < 0)
                || configuration.getAnchorWeights().values().stream()
                        .mapToInt(Integer::intValue).sum() <= 0
                || configuration.getStatusFactors().values().stream()
                        .anyMatch(factor -> factor == null
                                || !Double.isFinite(factor)
                                || factor < 0D
                                || factor > 1D)) {
            context.markFallback("AGGREGATION_RULE_INVALID");
        }
    }

    private boolean validConfidence(Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double confidence = ((Number) value).doubleValue();
        return Double.isFinite(confidence) && confidence >= 0D && confidence <= 1D;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
