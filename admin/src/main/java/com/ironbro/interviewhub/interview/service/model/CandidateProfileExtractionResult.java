package com.ironbro.interviewhub.interview.service.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CandidateProfileExtractionResult {

    private CandidateProfile profile;
    private Integer score;
    private List<String> resumeSuggest = new ArrayList<>();
    private List<String> resumeQuestion = new ArrayList<>();
    private String resumeType;
    private List<String> ragQuery = new ArrayList<>();
    private String rawResponse;
}
