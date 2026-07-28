package com.ironbro.interviewhub.interview.service.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class QuestionSpecParseResult {

    List<String> questions;
    Map<String, String> anchorsByQuestionNumber;
    Integer rubricVersion;
}
