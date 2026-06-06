package com.ironbro.interviewhub.ai.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * AI配置实体类
 * TODO: temperature 默认值后面抽成配置
 */
@Data
@TableName("ai_properties")
public class AiPropertiesDO {

    /**
     * 默认温度值
     */
    public static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");

    @TableId(type = IdType.AUTO)
    private Long id;

    private String aiName;
    private String aiType;
    private String apiKey;
    private String apiSecret;
    private String projectId;
    private String organizationId;
    private String apiUrl;
    private String modelName;
    private Integer maxTokens;
    private BigDecimal temperature;
    private String systemPrompt;
    private Integer isEnabled;

    /**
     * 是否开启思考模式（DeepSeek专用） 0：关闭 1：开启
     */
    private Integer enableThinking;

    /**
     * 思考模式预算Token数（DeepSeek专用）
     */
    private Integer thinkingBudgetTokens;

    private Date createTime;
    private Date updateTime;
    private Integer delFlag;
}
