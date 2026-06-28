package com.ironbro.interviewhub.media.api;

import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import com.ironbro.interviewhub.media.api.io.req.LongTextTtsReqDTO;
import com.ironbro.interviewhub.media.api.io.resp.LongTextTtsTaskRespDTO;
import com.ironbro.interviewhub.media.infrastructure.integration.XunfeiLongTextTtsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for Xunfei long-text TTS tasks.
 */
@RestController
@RequestMapping("/api/xunzhi/v1/xunfei/tts")
@RequiredArgsConstructor
public class XunfeiTtsController {

    private final XunfeiLongTextTtsService xunfeiLongTextTtsService;

    /**
     * Create an asynchronous TTS task.
     */
    @PostMapping("/tasks")
    public Result<LongTextTtsTaskRespDTO> createTask(@RequestBody LongTextTtsReqDTO requestParam) {
        return Results.success(xunfeiLongTextTtsService.createTask(requestParam));
    }

    /**
     * Query the current task status.
     */
    @GetMapping("/tasks/{taskId}")
    public Result<LongTextTtsTaskRespDTO> queryTask(@PathVariable String taskId) {
        return Results.success(xunfeiLongTextTtsService.queryTask(taskId));
    }

    /**
     * Create a task and wait for completion within the same request.
     */
    @PostMapping("/synthesize")
    public Result<LongTextTtsTaskRespDTO> synthesizeAndWait(@RequestBody LongTextTtsReqDTO requestParam) {
        return Results.success(xunfeiLongTextTtsService.synthesizeAndWait(requestParam));
    }
}
