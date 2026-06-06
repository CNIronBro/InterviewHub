package com.ironbro.interviewhub.ai.service.chat;

import com.ironbro.interviewhub.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;
import com.ironbro.interviewhub.toolkit.xunfei.AIContentAccumulator;
import reactor.core.publisher.FluxSink;

import java.util.List;

public interface AiChatHandler {

    String getType();

    void streamToSink(AiPropertiesDO aiProperties, String userMessage,
                      List<AiMessageHistoryRespDTO> historyMessages,
                      FluxSink<String> sink, AIContentAccumulator accumulator) throws Exception;
}
