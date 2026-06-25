package com.ironbro.interviewhub.interview.service;

import com.ironbro.interviewhub.interview.api.io.resp.RadarChartDTO;

public interface InterviewRadarService {

    RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

