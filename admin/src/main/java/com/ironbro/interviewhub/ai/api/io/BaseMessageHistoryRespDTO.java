package com.ironbro.interviewhub.ai.api.io;

import java.util.Date;

/**
 * 基础消息历史响应DTO接口
 */
public interface BaseMessageHistoryRespDTO {

    String getId();
    void setId(String id);

    String getSessionId();
    void setSessionId(String sessionId);

    Integer getMessageType();
    void setMessageType(Integer messageType);

    String getMessageContent();
    void setMessageContent(String messageContent);

    Integer getMessageSeq();
    void setMessageSeq(Integer messageSeq);

    Integer getTokenCount();
    void setTokenCount(Integer tokenCount);

    Integer getResponseTime();
    void setResponseTime(Integer responseTime);

    Date getCreateTime();
    void setCreateTime(Date createTime);
}
