package com.ironbro.interviewhub.ai.api.io.req;

import lombok.Data;

/**
 * AI会话分页查询请求DTO
 */
@Data
public class AiConversationPageReqDTO {

    private Integer current = 1;
    private Integer size = 10;
    private Long aiId;
    private Integer status;
    private String title;
}