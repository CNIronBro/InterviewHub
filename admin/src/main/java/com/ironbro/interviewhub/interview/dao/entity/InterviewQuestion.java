package com.ironbro.interviewhub.interview.dao.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * 面试题存储表
 */
@Data
@Document(collection = "interview_question")
public class InterviewQuestion {

    @Id
    private String id;

    @Indexed
    private String sessionId;

    private String userName;
    private List<String> questions;
    private List<String> suggestions;
    private Integer resumeScore;
    private String interviewType;

    /**
     * 难度：EASY / MEDIUM / HARD
     */
    private String difficulty;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 预计答题时长（分钟）
     */
    private Integer expectedDuration;

    @CreatedDate
    private Date createTime;

    @LastModifiedDate
    private Date updateTime;

    private Integer delFlag;
}