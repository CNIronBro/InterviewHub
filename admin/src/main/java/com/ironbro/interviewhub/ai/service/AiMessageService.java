package com.ironbro.interviewhub.ai.service;

import com.ironbro.interviewhub.ai.api.io.req.AiMessageReqDTO;
import reactor.core.publisher.Flux;

public interface AiMessageService {

    Flux<String> aiChatFlux(AiMessageReqDTO requestParam, String username);
}