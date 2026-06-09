package com.ironbro.interviewhub.ai.api.io.resp;

import lombok.Data;

/**
 * AI会话创建响应DTO
 */
@Data
public class AiSessionCreateRespDTO {

    private String sessionId;
    private String conversationTitle;
}