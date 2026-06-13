package com.ironbro.interviewhub.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 文件上传记录
 */
@Data
@TableName("agent_file_asset")
public class AgentFileAssetDO {

    private Long id;
    private Long agentId;
    private String sessionId;
    private String userName;
    private String bizType;
    private String sourcePlatform;
    private String fileName;
    private String fileExt;
    private String contentType;
    private Long fileSize;
    private String fileUrl;
    private Integer uploadStatus;
    private String remark;
    private Date createTime;
    private Date updateTime;
    private Integer delFlag;
}