package com.ironbro.interviewhub.agent.api.io.req;

import lombok.Data;

@Data
public class AgentPropertiesReqDTO {

    private Long id;
    private String agentName;
    private String apiSecret;
    private String apiKey;
    private String apiFlowId;
    private Integer pageNum;
    private Integer pageSize;
}