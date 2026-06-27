package com.ironbro.interviewhub.interview.application;

import com.ironbro.interviewhub.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewAnswerReqDTO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewQuestionReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewAnswerRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewQuestionRespDTO;

public interface InterviewWorkflowService {

    InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO requestParam);

    InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam);

    InterviewAnswerRespDTO getNextQuestion(String sessionId);

    InterviewAnswerRespDTO getCurrentQuestion(String sessionId);

    String evaluateDemeanor(DemeanorEvaluationReqDTO requestParam);
}
