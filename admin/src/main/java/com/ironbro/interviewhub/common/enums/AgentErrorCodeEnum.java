package com.ironbro.interviewhub.common.enums;

import com.ironbro.interviewhub.common.convention.errorcode.IErrorCode;

/**
 * 智能体错误码
 */
public enum AgentErrorCodeEnum implements IErrorCode {

    Agent_NULL("B000300", "智能体配置不存在"),
    AGENT_NAME_EXIST("B000301", "智能体已存在"),
    AGENT_EXIST("B000302", "智能体记录已存在"),
    AGENT_SAVE_ERROR("B000303", "智能体记录新增失败");

    private final String code;
    private final String message;

    AgentErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() { return code; }

    @Override
    public String message() { return message; }
}