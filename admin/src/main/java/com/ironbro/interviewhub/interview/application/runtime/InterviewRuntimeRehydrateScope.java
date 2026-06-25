package com.ironbro.interviewhub.interview.application.runtime;

/**
 * 定义面试会话运行态懒恢复的范围枚举，
 * 用于区分只恢复流程、得分、回放、材料或整包运行态等不同场景。
 */
public enum InterviewRuntimeRehydrateScope {

    /**
     * 仅恢复流程：题目材料 + flow + 追问题。
     */
    FLOW_ONLY,

    /**
     * 仅恢复分数：当前聚合分。
     */
    SCORE_ONLY,

    /**
     * 仅恢复回放数据：turns + requestId 去重集合。
     */
    PLAYBACK_ONLY,

    /**
     * 仅恢复材料底座：题目、建议、简历上下文、简历分、神态分、方向。
     */
    MATERIAL_ONLY,

    /**
     * 恢复热运行态：题目 + flow + 追问题 + score + turns + requestIds。
     */
    HOT_RUNTIME,

    /**
     * 恢复全量运行态：热运行态 + 材料底座的全部。
     */
    FULL_RUNTIME;

    public boolean includesQuestionMaterial() {
        return switch (this) {
            case FLOW_ONLY, MATERIAL_ONLY, HOT_RUNTIME, FULL_RUNTIME -> true;
            default -> false;
        };
    }

    public boolean includesSuggestionMaterial() {
        return this == MATERIAL_ONLY || this == FULL_RUNTIME;
    }

    public boolean includesResumeMaterial() {
        return this == MATERIAL_ONLY || this == FULL_RUNTIME;
    }

    public boolean includesFlow() {
        return this == FLOW_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesFollowUpQuestions() {
        return this == FLOW_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesScore() {
        return this == SCORE_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesTurns() {
        return this == PLAYBACK_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesRequestIds() {
        return includesTurns();
    }
}