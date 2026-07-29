package com.ironbro.interviewhub.interview.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.interview.service.model.CandidateProfile;
import org.springframework.stereotype.Component;

import java.util.Collections;
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
        profile.setRoleHypotheses(objectList(payload.get("roleHypotheses"), CandidateProfile.RoleHypothesis.class));
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
}
