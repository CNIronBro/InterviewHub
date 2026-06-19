package com.ironbro.interviewhub.interview.api.io.resp;

import lombok.Data;

@Data
public class InterviewAnswerRespDTO {

    private String questionNumber;
    private String questionContent;
    private Integer score;
    private Integer totalScore;
    private Boolean isSuccess;
    private String errorMessage;
    private String feedback;
    private String nextQuestion;
    private String nextQuestionNumber;
    private Boolean isFollowUp;
    private Integer followUpCount;
    private Boolean finished;

    public static InterviewAnswerRespDTO init() {
        InterviewAnswerRespDTO resp = new InterviewAnswerRespDTO();
        resp.setIsSuccess(false);
        resp.setFinished(false);
        resp.setIsFollowUp(false);
        resp.setFollowUpCount(0);
        return resp;
    }

    public InterviewAnswerRespDTO fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.isSuccess = false;
        return this;
    }

    public InterviewAnswerRespDTO success() {
        this.isSuccess = true;
        return this;
    }

    public InterviewAnswerRespDTO finish() {
        this.finished = true;
        this.isFollowUp = false;
        this.nextQuestion = null;
        this.nextQuestionNumber = null;
        return this;
    }
}