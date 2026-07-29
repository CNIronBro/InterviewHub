package com.ironbro.interviewhub.interview.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.service.model.CandidateProfile;
import com.ironbro.interviewhub.interview.service.model.CandidateProfileResolutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CandidateProfileResolver {

    public static final String DEFAULT_TARGET = "通用软件工程师";
    public static final String REASON_CONFIRMED_TARGET = "CONFIRMED_TARGET";
    public static final String REASON_ROLE_HYPOTHESIS = "ROLE_HYPOTHESIS";
    public static final String REASON_LEGACY_CONTEXT = "LEGACY_CONTEXT";
    public static final String REASON_DEFAULT_FALLBACK = "DEFAULT_FALLBACK";

    private final CandidateProfileParser candidateProfileParser;

    public CandidateProfileResolutionResult resolve(
            CandidateProfile source,
            String confirmedTarget,
            Map<String, Object> legacyResumeContext) {
        CandidateProfile profile = copyOrEmpty(source);
        ensureVersion(profile);

        String normalizedConfirmedTarget = StrUtil.trimToNull(confirmedTarget);
        if (normalizedConfirmedTarget != null) {
            profile.setConfirmedTarget(normalizedConfirmedTarget);
            return new CandidateProfileResolutionResult(
                    profile, normalizedConfirmedTarget, REASON_CONFIRMED_TARGET);
        }
        profile.setConfirmedTarget(null);

        CandidateProfile.RoleHypothesis best = highestConfidence(profile);
        if (best != null) {
            return new CandidateProfileResolutionResult(
                    profile, best.getTarget().trim(), REASON_ROLE_HYPOTHESIS);
        }

        Map<String, Object> safeLegacy =
                legacyResumeContext == null ? Collections.emptyMap() : legacyResumeContext;
        CandidateProfile legacyProfile = candidateProfileParser.parse(safeLegacy);
        CandidateProfile.RoleHypothesis legacyBest = highestConfidence(legacyProfile);
        if (legacyBest != null) {
            ensureVersion(legacyProfile);
            return new CandidateProfileResolutionResult(
                    legacyProfile, legacyBest.getTarget().trim(), REASON_LEGACY_CONTEXT);
        }
        String legacyTarget = firstNonBlank(
                safeLegacy.get("resumeType"),
                safeLegacy.get("type"),
                safeLegacy.get("interviewType"),
                safeLegacy.get("direction"),
                safeLegacy.get("interviewDirection"));
        if (legacyTarget != null) {
            return new CandidateProfileResolutionResult(
                    profile, legacyTarget, REASON_LEGACY_CONTEXT);
        }

        return new CandidateProfileResolutionResult(
                profile, DEFAULT_TARGET, REASON_DEFAULT_FALLBACK);
    }

    private CandidateProfile copyOrEmpty(CandidateProfile source) {
        if (source == null) {
            return new CandidateProfile();
        }
        try {
            CandidateProfile copy = JSON.parseObject(JSON.toJSONString(source), CandidateProfile.class);
            return copy == null ? new CandidateProfile() : copy;
        } catch (Exception ignored) {
            return new CandidateProfile();
        }
    }

    private void ensureVersion(CandidateProfile profile) {
        if (profile.getProfileVersion() == null || profile.getProfileVersion() < 1) {
            profile.setProfileVersion(1);
        }
    }

    /**
     * Stable tie rule: keep the first valid hypothesis in model output order.
     */
    private CandidateProfile.RoleHypothesis highestConfidence(CandidateProfile profile) {
        if (profile == null || profile.getRoleHypotheses() == null) {
            return null;
        }
        CandidateProfile.RoleHypothesis best = null;
        for (CandidateProfile.RoleHypothesis hypothesis : profile.getRoleHypotheses()) {
            if (!isValid(hypothesis)) {
                continue;
            }
            if (best == null || hypothesis.getConfidence() > best.getConfidence()) {
                best = hypothesis;
            }
        }
        return best;
    }

    private boolean isValid(CandidateProfile.RoleHypothesis hypothesis) {
        return hypothesis != null
                && StrUtil.isNotBlank(hypothesis.getTarget())
                && StrUtil.isNotBlank(hypothesis.getEvidence())
                && hypothesis.getConfidence() != null
                && hypothesis.getConfidence() >= 0D
                && hypothesis.getConfidence() <= 1D;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String normalized = value == null ? null : StrUtil.trimToNull(String.valueOf(value));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }
}
