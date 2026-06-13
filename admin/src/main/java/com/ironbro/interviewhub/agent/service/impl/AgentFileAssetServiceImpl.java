package com.ironbro.interviewhub.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ironbro.interviewhub.agent.api.io.resp.AgentFileUploadRespDTO;
import com.ironbro.interviewhub.agent.application.AgentResolver;
import com.ironbro.interviewhub.agent.dao.entity.AgentFileAssetDO;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.agent.dao.mapper.AgentFileAssetMapper;
import com.ironbro.interviewhub.agent.service.AgentFileAssetService;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import com.ironbro.interviewhub.common.enums.AgentErrorCodeEnum;
import com.ironbro.interviewhub.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFileAssetServiceImpl extends ServiceImpl<AgentFileAssetMapper, AgentFileAssetDO>
        implements AgentFileAssetService {

    private final XingChenAIClient xingChenAIClient;
    private final AgentResolver agentResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentFileUploadRespDTO uploadAndPersist(String sessionId, String bizType, String username, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException("上传文件不能为空", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        AgentPropertiesDO agentProperties = resolveAgent(sessionId);
        if (StrUtil.isBlank(agentProperties.getApiKey()) || StrUtil.isBlank(agentProperties.getApiSecret())) {
            throw new ClientException("Agent API 凭证缺失", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        String fileUrl;
        try {
            fileUrl = xingChenAIClient.uploadFile(file, agentProperties.getApiKey(), agentProperties.getApiSecret());
        } catch (Exception ex) {
            log.error("文件上传失败, agentId={}, fileName={}", agentProperties.getId(), file.getOriginalFilename(), ex);
            throw new ClientException("文件上传失败: " + ex.getMessage(), AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        String originalFileName = StrUtil.blankToDefault(file.getOriginalFilename(), "unknown");
        Date now = new Date();
        AgentFileAssetDO fileAssetDO = new AgentFileAssetDO();
        fileAssetDO.setAgentId(agentProperties.getId());
        fileAssetDO.setSessionId(StrUtil.isBlank(sessionId) ? null : sessionId.trim());
        fileAssetDO.setUserName(StrUtil.blankToDefault(username, "unknown"));
        fileAssetDO.setBizType(StrUtil.blankToDefault(bizType, "general"));
        fileAssetDO.setSourcePlatform("xingchen");
        fileAssetDO.setFileName(originalFileName.trim());
        fileAssetDO.setFileExt(extractFileExt(originalFileName));
        fileAssetDO.setContentType(file.getContentType());
        fileAssetDO.setFileSize(file.getSize());
        fileAssetDO.setFileUrl(fileUrl);
        fileAssetDO.setUploadStatus(1);
        fileAssetDO.setCreateTime(now);
        fileAssetDO.setUpdateTime(now);
        fileAssetDO.setDelFlag(0);

        if (!save(fileAssetDO)) {
            throw new ClientException("文件记录保存失败", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        AgentFileUploadRespDTO respDTO = new AgentFileUploadRespDTO();
        respDTO.setId(fileAssetDO.getId());
        respDTO.setSessionId(fileAssetDO.getSessionId());
        respDTO.setBizType(fileAssetDO.getBizType());
        respDTO.setFileName(fileAssetDO.getFileName());
        respDTO.setFileSize(fileAssetDO.getFileSize());
        respDTO.setContentType(fileAssetDO.getContentType());
        respDTO.setFileUrl(fileAssetDO.getFileUrl());
        respDTO.setCreateTime(fileAssetDO.getCreateTime());
        return respDTO;
    }

    private AgentPropertiesDO resolveAgent(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            AgentPropertiesDO boundAgent = agentResolver.resolveAgent(sessionId, null);
            if (boundAgent != null) return boundAgent;
        }
        throw new ClientException("无法解析Agent配置", AgentErrorCodeEnum.Agent_NULL);
    }

    private String extractFileExt(String fileName) {
        if (StrUtil.isBlank(fileName)) return null;
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return null;
        return fileName.substring(idx + 1).toLowerCase();
    }
}