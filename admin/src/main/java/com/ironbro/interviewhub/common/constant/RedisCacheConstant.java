package com.ironbro.interviewhub.common.constant;

/**
 * Redis 缓存常量类
 */
public class RedisCacheConstant {

    /**
     * 用户注册分布式锁
     */
    public static final String LOCK_USER_REGISTER_KEY = "interview-hub:lock_user-register:";

    /**
     * 用户登录缓存标识
     */
    public static final String USER_LOGIN_KEY = "interview-hub:login:";

    // ========== 面试模块 ==========

    /** 面试会话缓存前缀 */
    public static final String INTERVIEW_SESSION_PREFIX = "interview:session:";
    /** 面试运行时状态前缀 */
    public static final String INTERVIEW_RUNTIME_PREFIX = "interview:runtime:";
    /** 面试快照前缀 */
    public static final String INTERVIEW_SNAPSHOT_PREFIX = "interview:snapshot:";
    /** 面试分布式锁前缀 */
    public static final String INTERVIEW_LOCK_PREFIX = "interview:lock:";
    /** 面试 Single-flight 前缀 */
    public static final String INTERVIEW_FLIGHT_PREFIX = "interview:flight:";
}