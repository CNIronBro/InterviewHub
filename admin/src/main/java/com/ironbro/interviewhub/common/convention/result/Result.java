package com.ironbro.interviewhub.common.convention.result;

import com.ironbro.interviewhub.common.convention.errorcode.IErrorCode;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 全局返回对象
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 5679018624309023727L;

    /**
     * 正确返回码
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 返回码
     */
    private String code;

    /**
     * 返回消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 请求ID
     */
    private String requestId;

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }

    /**
     * 通过错误码构建失败响应
     */
    public static Result<Void> fail(IErrorCode errorCode) {
        Result<Void> result = new Result<>();
        result.setCode(errorCode.code());
        result.setMessage(errorCode.message());
        return result;
    }

    /**
     * 通过错误码和消息构建失败响应
     */
    public static Result<Void> fail(String code, String message) {
        Result<Void> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
