package com.ironbro.interviewhub.interview.application.rule.node;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Map;

/**
 * Determines whether an unresolved core anchor has candidate-authored technical evidence
 * that is worth verifying with a follow-up.
 */
final class FollowUpEvidencePolicy {

    private FollowUpEvidencePolicy() {
    }

    static boolean hasSubstantiveCoreEvidence(
            List<String> targetAnchorIds,
            List<Map<String, Object>> anchorJudgments) {
        if (targetAnchorIds == null || targetAnchorIds.isEmpty()
                || anchorJudgments == null || anchorJudgments.isEmpty()) {
            return false;
        }
        for (Map<String, Object> judgment : anchorJudgments) {
            if (judgment == null) {
                continue;
            }
            String anchorId = stringValue(judgment.get("anchorId"));
            if (StrUtil.isBlank(anchorId)) {
                anchorId = stringValue(judgment.get("name"));
            }
            if (!targetAnchorIds.contains(anchorId)) {
                continue;
            }
            String status = stringValue(judgment.get("status"));
            if ("partial".equalsIgnoreCase(status) || "contradicted".equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
