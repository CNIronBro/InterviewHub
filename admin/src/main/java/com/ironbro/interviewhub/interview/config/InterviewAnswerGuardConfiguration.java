package com.ironbro.interviewhub.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.answer-guard")
public class InterviewAnswerGuardConfiguration {

    private Long lockExpireSeconds = 120L;
    private Boolean lockWatchdogEnabled = true;
    private Long processingExpireSeconds = 120L;
    private Long processingLongTailExpireSeconds = 300L;
    private Long replayExpireHours = 24L;
    private Long lockWaitMillis = 0L;
}