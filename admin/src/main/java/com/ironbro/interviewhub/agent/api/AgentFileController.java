package com.ironbro.interviewhub.agent.api;

import com.ironbro.interviewhub.agent.api.io.resp.AgentFileUploadRespDTO;
import com.ironbro.interviewhub.agent.service.AgentFileAssetService;
import com.ironbro.interviewhub.common.convention.annotation.CurrentUser;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/xunzhi/v1/agents/files")
public class AgentFileController {

    private final AgentFileAssetService agentFileAssetService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AgentFileUploadRespDTO> upload(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestPart("file") MultipartFile file,
            @CurrentUser String username) {
        return Results.success(agentFileAssetService.uploadAndPersist(sessionId, bizType, username, file));
    }
}