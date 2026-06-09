package com.ironbro.interviewhub.ai.api;

import com.ironbro.interviewhub.ai.api.io.req.AiMessageReqDTO;
import com.ironbro.interviewhub.ai.service.AiMessageService;
import com.ironbro.interviewhub.common.convention.annotation.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiMessageController {

    private final AiMessageService aiMessageService;

    @PostMapping(value = "/sessions/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@PathVariable String sessionId,
                             @RequestBody AiMessageReqDTO requestParam,
                             @CurrentUser String username,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");
        requestParam.setSessionId(sessionId);
        return aiMessageService.aiChatFlux(requestParam, username);
    }
}