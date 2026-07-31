package com.ironbro.interviewhub.interview.application.rule.node;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts unresolved core anchors from the immutable question specification.
 */
@LiteflowComponent("coreAnchorAnalysis")
public class CoreAnchorAnalysisNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) return;

        Map<String, String> statusByAnchor = new LinkedHashMap<>();
        if (context.getAnchorJudgments() != null) {
            for (Map<String, Object> judgment : context.getAnchorJudgments()) {
                if (judgment == null) continue;
                String anchorId = stringValue(judgment.get("anchorId"));
                if (StrUtil.isBlank(anchorId)) {
                    anchorId = stringValue(judgment.get("name"));
                }
                if (StrUtil.isNotBlank(anchorId)) {
                    statusByAnchor.put(anchorId, stringValue(judgment.get("status")));
                }
            }
        }

        List<String> unresolvedCoreAnchors = new ArrayList<>();
        List<String> groundedTargetPoints = new ArrayList<>();
        if (StrUtil.isNotBlank(context.getQuestionSpecJson())) {
            try {
                Map<String, Object> spec = JSON.parseObject(context.getQuestionSpecJson(), Map.class);
                Object rawAnchors = spec == null ? null : spec.get("anchors");
                if (rawAnchors instanceof List<?> anchors) {
                    for (Object rawAnchor : anchors) {
                        if (!(rawAnchor instanceof Map<?, ?> anchor)) continue;
                        if (!Boolean.parseBoolean(stringValue(anchor.get("core")))) continue;
                        String anchorId = stringValue(anchor.get("name"));
                        if (StrUtil.isBlank(anchorId)) {
                            anchorId = stringValue(anchor.get("anchorId"));
                        }
                        String status = statusByAnchor.get(anchorId);
                        if (StrUtil.isNotBlank(anchorId) && !"met".equalsIgnoreCase(status)) {
                            unresolvedCoreAnchors.add(anchorId);
                            Object rawStatements = anchor.get("acceptableStatements");
                            if (rawStatements instanceof List<?> statements) {
                                for (Object statement : statements) {
                                    String groundedPoint = stringValue(statement);
                                    if (StrUtil.isNotBlank(groundedPoint) && groundedTargetPoints.size() < 4) {
                                        groundedTargetPoints.add(anchorId + ": " + groundedPoint);
                                    }
                                    if (groundedTargetPoints.size() >= 4) {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Missing/legacy specs are handled by the low-score fallback rule.
            }
        }

        context.setTargetAnchorIds(unresolvedCoreAnchors);
        context.setTargetMissingPoints(groundedTargetPoints);
        context.setSubstantiveCoreEvidence(
                FollowUpEvidencePolicy.hasSubstantiveCoreEvidence(
                        unresolvedCoreAnchors,
                        context.getAnchorJudgments()
                )
        );
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
