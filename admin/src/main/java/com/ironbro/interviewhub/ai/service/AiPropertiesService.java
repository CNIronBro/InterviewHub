package com.ironbro.interviewhub.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;

import java.util.List;

public interface AiPropertiesService extends IService<AiPropertiesDO> {

    AiPropertiesDO getDefaultDoubaoConfig();

    List<AiPropertiesDO> listEnabled();
}