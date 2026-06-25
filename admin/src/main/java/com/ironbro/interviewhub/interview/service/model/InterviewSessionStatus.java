package com.ironbro.interviewhub.interview.service.model;

/**
 * 面试会话状态枚举，定义面试的完整生命周期。
 * 状态流转：DRAFT → RESUME_UPLOADING → READY → IN_PROGRESS → FINISHED
 *           ↓ 上传失败回退                       用户放弃 ↓
 *         DRAFT（可重试）                     ABANDONED（已弃用）
 */
public enum InterviewSessionStatus {

    /** 草稿：刚创建会话，还未上传简历 */
    DRAFT,
    /** 简历上传中：正在上传简历到 OSS 并调用 AI 出题，防止并发重复处理 */
    RESUME_UPLOADING,
    /** 就绪：简历解析完成、面试题已生成，等待用户开始答题 */
    READY,
    /** 进行中：用户已开始答题，面试正在进行 */
    IN_PROGRESS,
    /** 已结束：面试完成，报告已生成 */
    FINISHED,
    /** 已弃用：用户主动放弃或超时未完成 */
    ABANDONED;

    /** 是否仍在活跃流程中（排除 FINISHED 和 ABANDONED） */
    public boolean isActive() {
        return this == DRAFT || this == RESUME_UPLOADING || this == READY || this == IN_PROGRESS;
    }

    /** 是否可继续答题（只有 READY 和 IN_PROGRESS 才允许提交答案） */
    public boolean canResume() {
        return this == READY || this == IN_PROGRESS;
    }
}