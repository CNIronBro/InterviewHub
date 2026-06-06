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
    private Date createTime;
    private Date updateTime;
    private Integer delFlag;
}
