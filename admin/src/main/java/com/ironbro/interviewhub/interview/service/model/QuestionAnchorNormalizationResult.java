package com.ironbro.interviewhub.interview.service.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class QuestionAnchorNormalizationResult {

    String content;
    List<Map<String, Object>> anchors;
    Integer rubricVersion;
    boolean legacyFallback;
    String fallbackReason;
}
