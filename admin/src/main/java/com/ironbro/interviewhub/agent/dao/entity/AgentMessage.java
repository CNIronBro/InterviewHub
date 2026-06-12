package com.ironbro.interviewhub.agent.dao.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 智能体消息表
 */
@Data
@Document(collection = "agent_message")
public class AgentMessage {

    @Id
    private String id;

    @Indexed
    private String sessionId;

    private Integer messageType;
    private String messageContent;
    private Integer messageSeq;
    private Long parentMsgId;
    private Integer tokenCount;
    private Integer responseTime;
    private String errorMessage;

    @CreatedDate
    private Date createTime;

    @LastModifiedDate
    private Date updateTime;

    private Integer delFlag;
}