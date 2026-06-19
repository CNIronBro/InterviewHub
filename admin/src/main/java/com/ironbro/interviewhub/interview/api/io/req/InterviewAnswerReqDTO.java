package com.ironbro.interviewhub.interview.api.io.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InterviewAnswerReqDTO {

    @NotBlank(message = "题号不能为空")
    @Size(max = 32)
    private String questionNumber;

    @NotBlank(message = "答案内容不能为空")
    @Size(max = 5000)
    private String answerContent;

    private String sessionId;

    @Size(max = 64)
    private String requestId;
}