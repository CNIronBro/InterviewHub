package com.ironbro.interviewhub.agent.api;

import com.ironbro.interviewhub.agent.api.io.req.AgentPropertiesReqDTO;
import com.ironbro.interviewhub.agent.api.io.resp.AgentPropertiesRespDTO;
import com.ironbro.interviewhub.agent.service.AgentPropertiesService;
import com.ironbro.interviewhub.common.convention.result.PageInfo;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * agent配置管理层
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent-properties")
public class AgentPropertiesController {

    private final AgentPropertiesService agentPropertiesService;

    @PostMapping
    public Result<Void> create(@RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.create(requestParam);
        return Results.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        agentPropertiesService.delete(id);
        return Results.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.update(requestParam);
        return Results.success();
    }

    @GetMapping("/byName")
    public Result<AgentPropertiesRespDTO> getByName(@RequestParam("name") String name) {
        return Results.success(agentPropertiesService.getByName(name));
    }

    @GetMapping
    public Result<PageInfo<AgentPropertiesRespDTO>> getByPage(AgentPropertiesReqDTO requestParam) {
        return Results.success(agentPropertiesService.getByPage(requestParam));
    }
}