package com.ironbro.interviewhub.interview.dao.entity;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 面试会话运行态快照，用于会话恢复
 */
@Data
public class InterviewSessionRuntimeSnapshot {

    private String id;
    private String sessionId;
    private Long userId;
    private String sessionStatus;
    /**
     * 版本号，CAS 防并发覆盖
     */
    private Long snapshotVersion;

    private String snapshotLevel;
    private String resumeFileUrl;
    private String interviewType;
    private String direction;

    /** 题目映射：题号 → 题目内容 */
    private Map<String, String> questions;
    /** 建议映射 */
    private Map<String, String> suggestions;
    /** 简历上下文 */
    private Map<String, Object> resumeContext;
    private Integer resumeScore;
    private Integer demeanorScore;

    /** 当前流程状态 */
    private String flowStatus;
    private Integer currentIndex;
    private Integer totalQuestions;
    private Integer followUpCount;
    private Integer maxFollowUp;

    /** 追问题目 */
    private Map<String, String> followUpQuestions;
    /** 最近轮次日志 */
    private List<String> recentTurns;
    private Integer recentTurnCount;
    private Long lastTurnSeq;
    private String lastAppliedRequestId;
    private String lastMutationId;
    private Date lastMutationTime;
    private Long materialVersion;

    private Date snapshotUpdatedAt;
    private Date createTime;
    private Date updateTime;
}
