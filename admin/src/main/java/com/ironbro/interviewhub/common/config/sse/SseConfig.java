package com.ironbro.interviewhub.common.config.sse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SSE配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sse")
public class SseConfig {

    /**
     * SSE连接超时时间（毫秒），默认5分钟
     */
    private Long timeout = 300000L;

    /**
     * 心跳间隔时间（毫秒），默认10秒
     */
    private Long heartbeatInterval = 10000L;

    /**
     * AI接口连接超时时间（毫秒），默认30秒
     */
    private Integer connectTimeout = 30000;

    /**
     * AI接口读取超时时间（毫秒），默认5分钟
     */
    private Integer readTimeout = 300000;

    /**
     * 是否启用心跳机制
     */
    private Boolean enableHeartbeat = true;
}