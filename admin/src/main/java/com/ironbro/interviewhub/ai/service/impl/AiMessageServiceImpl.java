package com.ironbro.interviewhub.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.ai.api.io.req.AiMessageReqDTO;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;
import com.ironbro.interviewhub.ai.dao.mapper.AiPropertiesMapper;
import com.ironbro.interviewhub.ai.service.AiMessageService;
import com.ironbro.interviewhub.ai.service.chat.AiChatHandler;
import com.ironbro.interviewhub.ai.service.chat.AiChatHandlerFactory;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import com.ironbro.interviewhub.toolkit.xunfei.AIContentAccumulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageServiceImpl implements AiMessageService {

    private final AiPropertiesMapper aiPropertiesMapper;
    private final AiChatHandlerFactory aiChatHandlerFactory;

    @Override
    public Flux<String> aiChatFlux(AiMessageReqDTO requestParam, String username) {
        if (requestParam == null) {
            return Flux.error(new ClientException("请求体不能为空"));
        }
        if (StrUtil.isBlank(requestParam.getSessionId())) {
            return Flux.error(new ClientException("sessionId不能为空"));
        }
        if (StrUtil.isBlank(username)) {
            return Flux.error(new ClientException("username不能为空"));
        }

        String sessionId = requestParam.getSessionId();
        String userMessage = StrUtil.blankToDefault(requestParam.getInputMessage(), "");
        Long aiId = requestParam.getAiId();

        return Flux.create(sink -> {
            AIContentAccumulator accumulator = new AIContentAccumulator();
            try {
                AiPropertiesDO aiProperties = resolveAiProperties(aiId);
                AiChatHandler handler = aiChatHandlerFactory.getHandler(aiProperties.getAiType());
                if (handler == null) {
                    sink.next("当前AI类型不支持");
                    accumulator.appendSimpleContent("当前AI类型不支持");
                    sink.complete();
                    return;
                }
                handler.streamToSink(aiProperties, userMessage,
                        Collections.emptyList(), sink, accumulator);
            } catch (Exception e) {
                log.error("AI chat error, sessionId={}", sessionId, e);
                if (!sink.isCancelled()) {
                    sink.next("抱歉，处理请求时发生错误");
                    sink.error(e);
                }
            }
        });
    }

    private AiPropertiesDO resolveAiProperties(Long aiId) {
        if (aiId == null) {
            throw new ClientException("AI配置ID不能为空");
        }
        AiPropertiesDO aiProperties = aiPropertiesMapper.selectById(aiId);
        if (aiProperties == null || aiProperties.getDelFlag() == 1 || aiProperties.getIsEnabled() == 0) {
            throw new ClientException("AI配置不存在或已禁用");
        }
        return aiProperties;
    }
}