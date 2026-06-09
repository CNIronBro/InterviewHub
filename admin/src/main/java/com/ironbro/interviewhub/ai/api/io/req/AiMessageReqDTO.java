package com.ironbro.interviewhub.ai.api.io.req;

import lombok.Data;

/**
 * AI消息请求DTO
 */
@Data
public class AiMessageReqDTO {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户输入消息
     */
    private String inputMessage;

    /**
     * AI配置ID
     */
    private Long aiId;

    /**
     * 用户名
     */
    private String userName;
}