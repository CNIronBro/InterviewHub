package com.ironbro.interviewhub.ai.api.io.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * AI会话响应DTO
 */
@Data
public class AiConversationRespDTO {

    private String sessionId;
    private String username;
    private Long aiId;
    private String aiName;
    private String title;
    private Integer status;
    private Integer messageCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date lastMessageTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;
}