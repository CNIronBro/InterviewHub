package com.ironbro.interviewhub.interview.service.model;

/**
 * 定义面试运行态恢复结果的置信度等级，
 * 用于标识恢复后的会话是否可写、是否仅可读以及是否处于终态。
 *
 */
public enum InterviewRuntimeConfidence {

    /**
     * 精确态：恢复结果可以视作完整运行态，与 Redis 在线态等效。
     */
    EXACT,

    /**
     * 推导态：运行态不是从 Redis 直接命中，而是从题目材料、轮次记录、
     * 快照等推导重建出来的。
     */
    DERIVED,

    /**
     * 只读态：系统能恢复出一部分内容（如题目、分数），但不足以安全支撑
     */
    READ_ONLY,

    /**
     * 终态：当前会话已处于结束状态（FINISHED / ABANDONED）。
     */
    TERMINAL
}