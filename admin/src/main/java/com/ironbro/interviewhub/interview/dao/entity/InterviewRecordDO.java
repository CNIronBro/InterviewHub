package com.ironbro.interviewhub.interview.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 面试记录实体
 */
@Data
@TableName("interview_record")
public class InterviewRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("interview_score")
    private Integer interviewScore;

    @TableField("resume_score")
    private Integer resumeScore;

    @TableField("interview_status")
    private String interviewStatus;

    @TableField("question_count")
    private Integer questionCount;

    @TableField("interviewer_agent_id")
    private Long interviewerAgentId;

    @TableField("interview_suggestions")
    private String interviewSuggestions;

    @TableField("interview_direction")
    private String interviewDirection;

    @TableField("start_time")
    private Date startTime;

    @TableField("end_time")
    private Date endTime;

    @TableField("duration_seconds")
    private Integer durationSeconds;

    /**
     * 会话快照（JSON）
     */
    @TableField("session_snapshot_json")
    private String sessionSnapshotJson;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableField("del_flag")
    private Integer delFlag;
}