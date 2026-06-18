package com.ironbro.interviewhub.interview.dao.entity;

import lombok.Data;

import java.util.Date;

/**
 * 面试会话运行态快照，用于会话恢复
 */
@Data
public class InterviewSessionRuntimeSnapshot {

    private String id;
    private String sessionId;
    private Long userId;
    private String sessionStatus;
    private Long snapshotVersion;
    private String resumeFileUrl;
    private String interviewType;
    private Date createTime;
    private Date updateTime;
}