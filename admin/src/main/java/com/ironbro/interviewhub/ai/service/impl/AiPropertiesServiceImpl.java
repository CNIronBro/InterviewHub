package com.ironbro.interviewhub.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;
import com.ironbro.interviewhub.ai.dao.mapper.AiPropertiesMapper;
import com.ironbro.interviewhub.ai.service.AiPropertiesService;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiPropertiesServiceImpl extends ServiceImpl<AiPropertiesMapper, AiPropertiesDO>
        implements AiPropertiesService {

    @Override
    public AiPropertiesDO getDefaultDoubaoConfig() {
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getDelFlag, 0)
                .eq(AiPropertiesDO::getIsEnabled, 1)
                .eq(AiPropertiesDO::getAiType, "doubao")
                .orderByDesc(AiPropertiesDO::getCreateTime)
                .last("LIMIT 1");
        AiPropertiesDO config = baseMapper.selectOne(queryWrapper);
        if (config == null) {
            throw new ClientException("豆包AI默认配置不存在或未启用");
        }
        return config;
    }

    @Override
    public List<AiPropertiesDO> listEnabled() {
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getDelFlag, 0)
                .eq(AiPropertiesDO::getIsEnabled, 1)
                .orderByDesc(AiPropertiesDO::getCreateTime);
        return baseMapper.selectList(queryWrapper);
    }
}