package com.ironbro.interviewhub.common.enums;

import com.ironbro.interviewhub.common.convention.errorcode.IErrorCode;

/**
 * Interview domain error codes.
 */
public enum InterviewErrorCodeEnum implements IErrorCode {

    SESSION_ID_EMPTY("B000400", "sessionId不能为空"),
    INVALID_USER_ID("B000401", "无效的用户ID"),
    CONVERSATION_NOT_FOUND("B000402", "会话不存在"),
    CONVERSATION_ACCESS_DENIED("B000403", "无权访问此会话"),
    DEMEANOR_FILE_UPLOAD_FAILED("B000404", "仪态图片上传失败"),
    DEMEANOR_USER_PHOTO_NOT_FOUND("B000405", "用户照片未找到"),
    AGENT_CONFIG_NOT_FOUND("B000406", "Agent配置不存在"),
    DEMEANOR_AI_RESPONSE_INVALID("B000407", "AI响应无效"),
    DEMEANOR_AI_RESPONSE_CONTENT_MISSING("B000408", "AI响应内容缺失"),
    DEMEANOR_AI_RESPONSE_FORMAT_ERROR("B000409", "AI响应格式错误"),
    DEMEANOR_AI_RESPONSE_PARSE_ERROR("B000410", "AI响应解析错误"),
    DEMEANOR_EVALUATION_FAILED("B000411", "仪态评估失败"),
    INTERVIEW_SESSION_NOT_FOUND("B000412", "面试会话不存在"),
    INTERVIEW_SESSION_ACCESS_DENIED("B000413", "无权访问此面试会话"),
    INTERVIEW_SESSION_INVALID_STATE("B000414", "面试会话状态无效"),
    AI_TIMEOUT("B000415", "AI调用超时，请重试"),
    AI_OVERLOADED("B000416", "AI服务繁忙，请重试"),
    AI_UNAVAILABLE("B000417", "AI服务不可用，请重试");

    private final String code;
    private final String message;

    InterviewErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() { return code; }

    @Override
    public String message() { return message; }
}