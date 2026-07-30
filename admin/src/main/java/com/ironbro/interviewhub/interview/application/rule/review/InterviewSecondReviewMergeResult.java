package com.ironbro.interviewhub.interview.application.rule.review;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InterviewSecondReviewMergeResult {

    private Integer finalScore;
    private Integer ruleScore;
    private List<Map<String, Object>> anchorJudgments;
    private String ruleVersion;
    private String finalStrategy;
    private boolean hidePreciseScore;
    private boolean needsReview;
}
