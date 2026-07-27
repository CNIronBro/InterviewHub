package com.ironbro.interviewhub.interview.dao.entity;

import com.ironbro.interviewhub.interview.api.io.req.DemeanorScoreDTO;
import com.ironbro.interviewhub.interview.service.model.InterviewFlowState;
import com.ironbro.interviewhub.interview.service.model.InterviewRuntimeConfidence;
import com.ironbro.interviewhub.interview.service.model.InterviewRuntimeScoreAggregate;
import com.ironbro.interviewhub.interview.service.model.InterviewTurnLog;
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

    private InterviewRuntimeConfidence rebuildConfidence;

    private Date snapshotUpdatedAt;

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

    private DemeanorScoreDTO demeanorDetails;

    private InterviewFlowState flow;

    private InterviewRuntimeScoreAggregate scoreAggregate;

    private Map<String, String> followUpQuestions;

    private List<InterviewTurnLog> recentTurns;

    private Integer recentTurnCount;

    private Long archiveWatermark;

    private Long lastTurnSeq;
    private String lastAppliedRequestId;
    private String lastMutationId;
    private Date lastMutationTime;

    private String lastCommittedQuestionNumber;

    private String lastCommittedTurnDigest;

    private Long materialVersion;

    private Date createTime;
    private Date updateTime;
}
