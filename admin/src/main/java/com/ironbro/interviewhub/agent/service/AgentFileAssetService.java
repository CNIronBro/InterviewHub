package com.ironbro.interviewhub.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.agent.api.io.resp.AgentFileUploadRespDTO;
import com.ironbro.interviewhub.agent.dao.entity.AgentFileAssetDO;
import org.springframework.web.multipart.MultipartFile;

public interface AgentFileAssetService extends IService<AgentFileAssetDO> {

    AgentFileUploadRespDTO uploadAndPersist(String sessionId, String bizType, String username, MultipartFile file);
}