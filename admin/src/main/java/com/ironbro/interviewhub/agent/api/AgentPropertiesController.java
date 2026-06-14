package com.ironbro.interviewhub.agent.api;

import com.ironbro.interviewhub.agent.api.io.req.AgentPropertiesReqDTO;
import com.ironbro.interviewhub.agent.api.io.resp.AgentPropertiesRespDTO;
import com.ironbro.interviewhub.agent.service.AgentPropertiesService;
import com.ironbro.interviewhub.common.convention.result.PageInfo;
import com.ironbro.interviewhub.common.convention.result.Result;
import com.ironbro.interviewhub.common.convention.result.Results;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 配置管理接口
 * 提供智能体的增删改查、按名称查询和分页查询功能
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent-properties")
public class AgentPropertiesController {

    private final AgentPropertiesService agentPropertiesService;

    /**
     * 创建 Agent 配置
     */
    @PostMapping
    public Result<Void> create(@Valid @RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.create(requestParam);
        return Results.success();
    }

    /**
     * 根据 ID 删除 Agent 配置（软删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        agentPropertiesService.delete(id);
        return Results.success();
    }

    /**
     * 更新 Agent 配置
     */
    @PutMapping
    public Result<Void> update(@Valid @RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.update(requestParam);
        return Results.success();
    }

    /**
     * 根据名称查询 Agent 配置
     */
    @GetMapping("/byName")
    public Result<AgentPropertiesRespDTO> getByName(@RequestParam("name") String name) {
        return Results.success(agentPropertiesService.getByName(name));
    }

    /**
     * 分页查询 Agent 配置，支持时间排序和标签筛选
     */
    @GetMapping
    public Result<PageInfo<AgentPropertiesRespDTO>> getByPage(AgentPropertiesReqDTO requestParam) {
        return Results.success(agentPropertiesService.getByPage(requestParam));
    }
}