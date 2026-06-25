package com.ironbro.interviewhub.interview.service.model;

/**
 * 定义面试运行态恢复时的加载模式，
 * 用于区分只读查询场景和要求可写恢复的业务场景。
 */
public enum InterviewRuntimeLoadMode {

    /**
     * 只读模式：这次请求只需要展示/查询/预览，不需要继续推进会话。
     */
    READ_ONLY,

    /**
     * 读写模式：这次请求接下来还要继续写会话状态（推进题号、入账分数等）。
     */
    READ_WRITE_REQUIRED
}