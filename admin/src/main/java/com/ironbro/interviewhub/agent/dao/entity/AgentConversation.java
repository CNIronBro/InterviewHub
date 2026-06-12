package com.ironbro.interviewhub.agent.dao.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 智能体会话表
 */
@Data
@Document(collection = "agent_conversation")
public class AgentConversation {

    @Id
    private String id;

    @Indexed(unique = true)
    private String sessionId;

    @Indexed
    private Long userId;

    @Indexed
    private Long agentId;

    private String conversationTitle;
    private Integer messageCount;
    private Integer totalTokens;
    private Integer status;

    @CreatedDate
    private Date createTime;

    @LastModifiedDate
    private Date updateTime;

    private Integer delFlag;
}