package com.ironbro.interviewhub.interview.application.runtime;

/**
 * 热快照刷新策略枚举：将业务事件类型标准化映射为快照刷新策略。
 */
public enum InterviewSessionRuntimeHotRefreshTrigger {

    /**
     * 题目抽取完成：题目、建议、简历上下文等基础材料已就绪。
     */
    QUESTION_READY("QUESTION_READY", false, false, 10),

    /**
     * 神态评估完成：用户仪态评分结果已生成。
     */
    DEMEANOR_READY("DEMEANOR_READY", false, false, 20),

    /**
     * 一轮答题已成功提交：评分确认、turnLog 形成、幂等标记完成。
     */
    ANSWER_COMMITTED("ACTIVE", true, true, 30),

    /**
     * 面试会话已收尾完成：进入 FINISHED 或 ABANDONED 终态。
     */
    FINALIZED("FINALIZED", true, false, 40);

    /**
     * 写入 Hot Snapshot 时的业务阶段标识：
     * DRAFT → QUESTION_READY → ACTIVE → FINALIZED
     */
    private final String snapshotLevel;

    /** 是否需要强制立即刷新（跳过防抖等待，直接调 flushBucket） */
    private final boolean forceFlush;

    /** 是否需要同步写入 Turn Archive（追加一条不可变轮次记录） */
    private final boolean persistTurnArchive;

    /**
     * Bucket 内合并时的优先级：数值越大越优先。
     * 当同一 session 短时间内发生多个事件时，高优先级的 trigger
     * 会覆盖低优先级的 pendingTrigger 和 pendingSnapshotLevel。
     */
    private final int priority;

    InterviewSessionRuntimeHotRefreshTrigger(
            String snapshotLevel,
            boolean forceFlush,
            boolean persistTurnArchive,
            int priority) {
        this.snapshotLevel = snapshotLevel;
        this.forceFlush = forceFlush;
        this.persistTurnArchive = persistTurnArchive;
        this.priority = priority;
    }

    public String getSnapshotLevel() {
        return snapshotLevel;
    }

    public boolean isForceFlush() {
        return forceFlush;
    }

    public boolean isPersistTurnArchive() {
        return persistTurnArchive;
    }

    public int getPriority() {
        return priority;
    }
}