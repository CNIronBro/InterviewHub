package com.ironbro.interviewhub.ai.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ironbro.interviewhub.ai.api.io.req.AiMessageReqDTO;
import com.ironbro.interviewhub.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.ironbro.interviewhub.ai.service.AiMessageService;
import com.ironbro.interviewhub.common.convention.annotation.CurrentUser;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/xunzhi/v1/ai")
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
        response.setHeader("Access-Control-Allow-Headers", "Cache-Control");
        requestParam.setSessionId(sessionId);
        return aiMessageService.aiChatFlux(requestParam, username);
    }

    @GetMapping("/history/{sessionId}")
    public Result<List<AiMessageHistoryRespDTO>> getConversationHistory(@PathVariable String sessionId,
                                                                        @CurrentUser String username) {
        List<AiMessageHistoryRespDTO> result = aiMessageService.getConversationHistory(sessionId, username);
        return Results.success(result);
    }

    @GetMapping("/history/page")
    public Result<IPage<AiMessageHistoryRespDTO>> pageHistoryMessages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @CurrentUser String username) {
        IPage<AiMessageHistoryRespDTO> result = aiMessageService.pageHistoryMessages(sessionId, current, size, username);
        return Results.success(result);
    }
}
