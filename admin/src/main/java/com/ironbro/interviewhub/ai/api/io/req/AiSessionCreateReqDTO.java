package com.ironbro.interviewhub.ai.api.io.req;

import lombok.Data;

/**
 * AI会话创建请求DTO
 */
@Data
public class AiSessionCreateReqDTO {

    private Long aiId;
    private String firstMessage;
}