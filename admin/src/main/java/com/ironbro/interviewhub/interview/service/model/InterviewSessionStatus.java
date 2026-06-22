package com.ironbro.interviewhub.interview.service.model;

/**
 * 面试会话状态枚举
 * 流转：DRAFT → RESUME_UPLOADING → READY → IN_PROGRESS → FINISHED
 */
public enum InterviewSessionStatus {

    DRAFT,
    RESUME_UPLOADING,
    READY,
    IN_PROGRESS,
    FINISHED,
    ABANDONED;

    public boolean isActive() {
        return this == DRAFT || this == RESUME_UPLOADING || this == READY || this == IN_PROGRESS;
    }

    public boolean canResume() {
        return this == READY || this == IN_PROGRESS;
    }
}