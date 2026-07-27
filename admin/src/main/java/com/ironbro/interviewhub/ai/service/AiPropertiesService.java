package com.ironbro.interviewhub.ai.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.ai.api.io.req.AiPropertiesCreateReqDTO;
import com.ironbro.interviewhub.ai.api.io.req.AiPropertiesPageReqDTO;
import com.ironbro.interviewhub.ai.api.io.req.AiPropertiesUpdateReqDTO;
import com.ironbro.interviewhub.ai.api.io.resp.AiModelOptionRespDTO;
import com.ironbro.interviewhub.ai.api.io.resp.AiPropertiesRespDTO;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;

import java.util.List;

public interface AiPropertiesService extends IService<AiPropertiesDO> {

    void createAiProperties(AiPropertiesCreateReqDTO requestParam);

    void updateAiProperties(AiPropertiesUpdateReqDTO requestParam);

    void deleteAiProperties(Long id);

    AiPropertiesRespDTO getAiPropertiesById(Long id);

    IPage<AiPropertiesRespDTO> pageAiProperties(AiPropertiesPageReqDTO requestParam);

    List<AiPropertiesRespDTO> listEnabledAiProperties();

    void toggleAiPropertiesStatus(Long id, Integer isEnabled);

    List<AiPropertiesRespDTO> getAllEnabledAiProperties();

    AiPropertiesDO getEnabledByAiType(String aiType);

    AiPropertiesDO getDefaultDoubaoConfig();

    /**
     * 获取所有可用AI模型（用于前端下拉列表）
     */
    List<AiModelOptionRespDTO> getAvailableAiModels();
}

