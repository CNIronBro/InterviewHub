package com.ironbro.interviewhub.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.agent.api.io.req.AgentPropertiesReqDTO;
import com.ironbro.interviewhub.agent.api.io.resp.AgentPropertiesRespDTO;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.common.convention.result.PageInfo;

import java.util.List;

public interface AgentPropertiesService extends IService<AgentPropertiesDO> {

    void create(AgentPropertiesReqDTO requestParam);

    void delete(Long id);

    void update(AgentPropertiesReqDTO requestParam);

    AgentPropertiesRespDTO getByName(String name);

    PageInfo<AgentPropertiesRespDTO> getByPage(AgentPropertiesReqDTO requestParam);

    List<AgentPropertiesDO> listActiveAgents();
}