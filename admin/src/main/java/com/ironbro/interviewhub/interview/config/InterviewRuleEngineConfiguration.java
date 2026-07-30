package com.ironbro.interviewhub.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.rule-engine")
public class InterviewRuleEngineConfiguration {

    private Boolean enable = true;
    private String defaultChainId = "default_followup_chain";
    private Boolean failOpen = true;
    private String ruleVersion = "v2.0.0";
    private Integer defaultMaxFollowUp = 1;
    private Integer defaultLowScoreThreshold = 60;
    private Integer defaultHighQualityThreshold = 85;
}
