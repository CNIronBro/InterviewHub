package com.ironbro.interviewhub.agent.api.io.req;

import lombok.Data;

import java.util.List;

@Data
public class AgentPropertiesReqDTO {

    private Long id;
    private String agentName;
    private String apiSecret;
    private String apiKey;
    private String apiFlowId;
    private Integer pageNum;
    private Integer pageSize;

    /**
     * 标签代码列表，用于筛选
     */
    private List<Integer> tagCodes;

    /**
     * 时间排序：asc / desc
     */
    private String timeSort;
}