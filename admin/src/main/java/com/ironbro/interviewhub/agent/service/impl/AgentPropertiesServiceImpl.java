package com.ironbro.interviewhub.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ironbro.interviewhub.agent.api.io.req.AgentPropertiesReqDTO;
import com.ironbro.interviewhub.agent.api.io.resp.AgentPropertiesRespDTO;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.agent.dao.mapper.AgentPropertiesMapper;
import com.ironbro.interviewhub.agent.service.AgentPropertiesService;
import com.ironbro.interviewhub.common.convention.result.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentPropertiesServiceImpl extends ServiceImpl<AgentPropertiesMapper, AgentPropertiesDO>
        implements AgentPropertiesService {

    @Override
    @Transactional
    public void create(AgentPropertiesReqDTO requestParam) {
        AgentPropertiesDO agentPropertiesDO = new AgentPropertiesDO();
        BeanUtils.copyProperties(requestParam, agentPropertiesDO);
        agentPropertiesDO.setCreateTime(new Date());
        agentPropertiesDO.setUpdateTime(new Date());
        agentPropertiesDO.setDelFlag(0);
        save(agentPropertiesDO);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        LambdaUpdateWrapper<AgentPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getId, id)
                .set(AgentPropertiesDO::getDelFlag, 1)
                .set(AgentPropertiesDO::getUpdateTime, new Date());
        baseMapper.update(null, updateWrapper);
    }

    @Override
    @Transactional
    public void update(AgentPropertiesReqDTO requestParam) {
        LambdaUpdateWrapper<AgentPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getId, requestParam.getId())
                .set(AgentPropertiesDO::getAgentName, requestParam.getAgentName())
                .set(AgentPropertiesDO::getApiSecret, requestParam.getApiSecret())
                .set(AgentPropertiesDO::getApiKey, requestParam.getApiKey())
                .set(AgentPropertiesDO::getApiFlowId, requestParam.getApiFlowId())
                .set(AgentPropertiesDO::getUpdateTime, new Date());
        update(updateWrapper);
    }

    @Override
    public AgentPropertiesRespDTO getByName(String name) {
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getAgentName, name)
                .eq(AgentPropertiesDO::getDelFlag, 0);
        AgentPropertiesDO agentPropertiesDO = baseMapper.selectOne(queryWrapper);
        AgentPropertiesRespDTO result = new AgentPropertiesRespDTO();
        if (agentPropertiesDO != null) {
            BeanUtils.copyProperties(agentPropertiesDO, result);
        }
        return result;
    }

    @Override
    public PageInfo<AgentPropertiesRespDTO> getByPage(AgentPropertiesReqDTO requestParam) {
        Page<AgentPropertiesDO> page = new Page<>(requestParam.getPageNum(), requestParam.getPageSize());
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getDelFlag, 0)
                .orderByDesc(AgentPropertiesDO::getCreateTime);
        Page<AgentPropertiesDO> agentPropertiesDOPage = baseMapper.selectPage(page, queryWrapper);
        List<AgentPropertiesRespDTO> resultList = agentPropertiesDOPage.getRecords().stream()
                .map(item -> {
                    AgentPropertiesRespDTO respDTO = new AgentPropertiesRespDTO();
                    BeanUtils.copyProperties(item, respDTO);
                    return respDTO;
                })
                .collect(Collectors.toList());
        PageInfo<AgentPropertiesRespDTO> pageInfo = new PageInfo<>();
        pageInfo.setRecords(resultList);
        pageInfo.setTotal(agentPropertiesDOPage.getTotal());
        pageInfo.setCurrent(agentPropertiesDOPage.getCurrent());
        pageInfo.setPages(agentPropertiesDOPage.getPages());
        pageInfo.setSize(agentPropertiesDOPage.getSize());
        return pageInfo;
    }

    @Override
    public List<AgentPropertiesDO> listActiveAgents() {
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getDelFlag, 0)
                .orderByDesc(AgentPropertiesDO::getCreateTime);
        return baseMapper.selectList(queryWrapper);
    }
}