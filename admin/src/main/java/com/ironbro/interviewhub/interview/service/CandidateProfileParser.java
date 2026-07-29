package com.ironbro.interviewhub.interview.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.service.model.CandidateProfile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CandidateProfileParser {

    public CandidateProfile parse(Map<String, Object> payload) {
        CandidateProfile profile = new CandidateProfile();
        if (payload == null || payload.isEmpty()) {
            return profile;
        }
        profile.setSkills(stringList(payload.get("skills")));
        profile.setSkillEvidence(objectList(payload.get("skillEvidence"), CandidateProfile.SkillEvidence.class));
        profile.setRoleHypotheses(roleHypotheses(payload.get("roleHypotheses")));
        String confirmedTarget = payload.get("confirmedTarget") == null
                ? null : String.valueOf(payload.get("confirmedTarget")).trim();
        profile.setConfirmedTarget(StrUtil.isBlank(confirmedTarget) ? null : confirmedTarget);
        Integer profileVersion = integer(payload.get("profileVersion"));
        if (profileVersion != null && profileVersion > 0) {
            profile.setProfileVersion(profileVersion);
        }
        profile.getSkillEvidence().removeIf(item ->
                item == null || StrUtil.isBlank(item.getSkill()) || StrUtil.isBlank(item.getEvidence()));
        profile.getRoleHypotheses().removeIf(item ->
                item == null
                        || StrUtil.isBlank(item.getTarget())
                        || StrUtil.isBlank(item.getEvidence())
                        || item.getConfidence() == null
                        || item.getConfidence() < 0D
                        || item.getConfidence() > 1D);
        return profile;
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        try {
            List<String> values = JSON.parseArray(JSON.toJSONString(value), String.class);
            values.removeIf(StrUtil::isBlank);
            return values;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private <T> List<T> objectList(Object value, Class<T> type) {
        if (value == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(JSON.toJSONString(value), type);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<CandidateProfile.RoleHypothesis> roleHypotheses(Object value) {
        List<Map> rawItems = objectList(value, Map.class);
        if (rawItems.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(rawItems.stream().map(item -> {
            CandidateProfile.RoleHypothesis hypothesis = new CandidateProfile.RoleHypothesis();
            Object target = item.get("target") == null ? item.get("role") : item.get("target");
            hypothesis.setTarget(target == null ? null : String.valueOf(target).trim());
            hypothesis.setEvidence(item.get("evidence") == null
                    ? null : String.valueOf(item.get("evidence")).trim());
            Object confidence = item.get("confidence");
            if (confidence instanceof Number number) {
                hypothesis.setConfidence(number.doubleValue());
            } else if (confidence != null) {
                try {
                    hypothesis.setConfidence(Double.parseDouble(String.valueOf(confidence).trim()));
                } catch (NumberFormatException ignored) {
                    hypothesis.setConfidence(null);
                }
            }
            return hypothesis;
        }).toList());
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
