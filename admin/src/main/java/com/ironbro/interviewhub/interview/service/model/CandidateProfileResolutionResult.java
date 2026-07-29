package com.ironbro.interviewhub.interview.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CandidateProfileResolutionResult {

    private CandidateProfile profile;
    private String finalTarget;
    private String reason;
}
