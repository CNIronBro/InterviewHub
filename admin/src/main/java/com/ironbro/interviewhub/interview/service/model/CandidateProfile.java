package com.ironbro.interviewhub.interview.service.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CandidateProfile {

    private List<String> skills = new ArrayList<>();
    private List<SkillEvidence> skillEvidence = new ArrayList<>();
    private List<RoleHypothesis> roleHypotheses = new ArrayList<>();
    private String confirmedTarget;
    private Integer profileVersion = 1;

    @Data
    public static class SkillEvidence {
        private String skill;
        private String evidence;
    }

    @Data
    public static class RoleHypothesis {
        private String target;
        private Double confidence;
        private String evidence;
    }
}
