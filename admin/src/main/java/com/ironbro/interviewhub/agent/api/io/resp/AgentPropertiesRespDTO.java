package com.ironbro.interviewhub.agent.api.io.resp;

import lombok.Data;

@Data
public class AgentPropertiesRespDTO {

    private Long id;
    private String agentName;
    private String apiSecret;
    private String apiKey;
    private String apiFlowId;
}