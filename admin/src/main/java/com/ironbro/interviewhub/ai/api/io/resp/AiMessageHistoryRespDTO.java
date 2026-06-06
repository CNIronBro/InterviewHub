package com.ironbro.interviewhub.ai.api.io.resp;

import com.ironbro.interviewhub.ai.api.io.BaseMessageHistoryRespDTO;
import lombok.Data;

import java.util.Date;

/**
 * AI消息历史响应DTO
 */
@Data
public class AiMessageHistoryRespDTO implements BaseMessageHistoryRespDTO {

    private String id;
    private String sessionId;
    private Integer messageType;
    private String messageContent;
    private Integer messageSeq;
    private Integer tokenCount;
    private Integer responseTime;
    private String errorMessage;
    private Date createTime;
}
