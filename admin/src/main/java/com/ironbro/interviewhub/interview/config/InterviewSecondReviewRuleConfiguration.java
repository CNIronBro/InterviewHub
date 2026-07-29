package com.ironbro.interviewhub.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.second-review-rule-engine")
public class InterviewSecondReviewRuleConfiguration {

    private Boolean enable = true;
    private String defaultChainId = "second_review_chain";
    private Boolean failOpen = true;
    private String ruleVersion = "v1.0.0";
    private List<Integer> boundaryScores = new ArrayList<>(List.of(40, 60, 85));
    private Double lowConfidenceThreshold = 0.7D;
    private String criticalAnchorId = "correctness";
    private String anchorConflictAction = "CONSERVATIVE_RESULT";
}
