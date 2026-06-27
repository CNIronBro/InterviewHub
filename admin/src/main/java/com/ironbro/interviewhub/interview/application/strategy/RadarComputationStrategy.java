package com.ironbro.interviewhub.interview.application.strategy;

import com.ironbro.interviewhub.interview.api.io.resp.RadarChartDTO;

public interface RadarComputationStrategy {

    RadarChartDTO compute(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

