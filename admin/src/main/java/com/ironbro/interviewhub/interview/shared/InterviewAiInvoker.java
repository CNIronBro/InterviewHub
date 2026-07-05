package com.ironbro.interviewhub.interview.shared;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.application.guard.core.AiCallGuardService;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardStage;
import com.ironbro.interviewhub.interview.application.guard.singleflight.service.DistributedInterviewAiSingleFlightService;
import com.ironbro.interviewhub.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 面试 AI 的统一调用入口，负责生成请求指纹、串联限流熔断保护、
 * 分布式 single-flight 复用以及最终的模型调用过程。
 *
 * 【架构位置】所有 AI 调用的总入口，业务层不直接调用模型，全部经过此类。
 * 【调用链】业务Service → InterviewAiInvoker → SingleFlight去重 → Guard限流熔断 → XingChenAIClient
 *
 */
@Component
@RequiredArgsConstructor
public class InterviewAiInvoker {

    // 底层 AI 客户端（讯星辰大模型），负责真正的 HTTP 流式调用
    private final XingChenAIClient xingChenAIClient;
    // 限流熔断保护层（Sentinel），防止 AI 接口被打爆
    private final AiCallGuardService aiCallGuardService;
    // 分布式 single-flight 服务，把重复请求折叠成一次调用
    private final DistributedInterviewAiSingleFlightService distributedInterviewAiSingleFlightService;

    /**
     * 最简调用：纯文本 prompt + 默认走"面试评分"阶段
     * 用于：面试评分、追问生成等不需要文件的场景
     */
    public String callAiSync(String prompt, String sessionId, AgentPropertiesDO agentProperties) throws Exception {
        // 用 stage + sessionId + prompt 内容哈希 作为去重 key
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, prompt);
        return callAiSync(prompt, sessionId, agentProperties, InterviewAiGuardStage.INTERVIEW_EVALUATION, key);
    }

    /**
     * 通用纯文本调用，允许指定 stage（业务阶段）和 singleFlightKey（去重键）
     * stage 用于决定 single-flight 的 TTL、心跳等差异化策略
     */
    public String callAiSync(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String stage,
            String singleFlightKey) throws Exception {
        // guardedCall = SingleFlight去重 → Guard限流熔断 → doChat真实调用
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, null, null));
    }

    /**
     * 带文件的调用：用于表情/仪态分析等需要传图片的场景
     * key 用 fileUrl 作为 businessKey（同一张图片 = 同一次调用）
     */
    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl) throws Exception {
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_DEMEANOR, sessionId, fileUrl);
        return callAiSyncWithFile(prompt, sessionId, agentProperties, fileUrl, InterviewAiGuardStage.INTERVIEW_DEMEANOR, key);
    }

    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            String stage,
            String singleFlightKey) throws Exception {
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, fileUrl, null));
    }

    /**
     * 带参数的调用：用于 Agent 模式，通过 parameters Map 传递结构化参数给大模型
     * 从 parameters 中提取 AGENT_USER_INPUT 作为 key 的内容哈希来源
     */
    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters) throws Exception {
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, input);
        return callAiSyncWithParameters(
                sessionId,
                agentProperties,
                parameters,
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                key
        );
    }

    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters,
            String stage,
            String singleFlightKey) throws Exception {
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        return guardedCall(
                stage,
                singleFlightKey,
                () -> doChat(StrUtil.blankToDefault(input, ""), sessionId, agentProperties, null, parameters)
        );
    }

    /**
     * 构建 SingleFlight 去重 key（文本类调用专用）
     * 格式：stage|sessionId|questionNumber|answerHash
     *
     * 核心思想：同一 stage + 同一 session + 同一题号 + 相同答案内容 = 同一次调用
     * answerContent 取 SHA256 前 16 位，既保证唯一又控制 key 长度
     *
     * 面试要点：key 的粒度决定了"什么算重复"，过粗会误复用，过细起不到去重效果
     */
    public String buildSingleFlightKey(
            String stage,
            String sessionId,
            String questionNumber,
            String answerContent) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeQuestionNumber = StrUtil.blankToDefault(StrUtil.trimToEmpty(questionNumber), "-");
        String safeAnswerHash = StrUtil.isBlank(answerContent)
                ? "-"
                : DigestUtil.sha256Hex(answerContent.trim()).substring(0, 16);
        return safeStage + "|" + safeSessionId + "|" + safeQuestionNumber + "|" + safeAnswerHash;
    }

    /**
     * 构建 SingleFlight 去重 key（文件/材料类调用专用）
     * 格式：stage|sessionId|businessKey
     *
     * businessKey 通常是 fileUrl，同一张图片不会重复分析
     */
    public String buildSingleFlightKey(String stage, String sessionId, String businessKey) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeBusinessKey = StrUtil.blankToDefault(StrUtil.trimToEmpty(businessKey), "-");
        return safeStage + "|" + safeSessionId + "|" + safeBusinessKey;
    }

    /**
     * 核心调用链：SingleFlight去重 → Guard限流熔断 → 真实AI调用
     *
     * 注意 lambda 的嵌套顺序：
     *   1. distributedInterviewAiSingleFlightService.execute() — 先去重
     *   2.   aiCallGuardService.execute() — 再限流熔断
     *   3.     callable (即 doChat) — 最后才真正调用 AI
     *
     * 这样设计的好处：重复请求连限流令牌都不会消耗，直接复用结果
     */
    private String guardedCall(String stage, String singleFlightKey, Callable<String> callable) throws Exception {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String key = StrUtil.blankToDefault(singleFlightKey, safeStage + "|no-key");
        return distributedInterviewAiSingleFlightService.execute(
                safeStage,
                key,
                () -> aiCallGuardService.execute(safeStage, key, callable)
        );
    }

    /**
     * 真正的 AI 调用：通过讯星辰 SDK 发送请求，流式接收响应
     * 用 OutputStream 把流式片段拼接成完整字符串返回
     */
    private String doChat(
            String input,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            Map<String, Object> parameters) throws Exception {
        StringBuilder aiResponse = new StringBuilder();
        // 走底层 chat，把流式片段拼成完整响应字符串返回上层解析
        xingChenAIClient.chat(
                input,                // 用户输入（回答内容 / prompt）
                StrUtil.isNotBlank(sessionId) ? sessionId : "evaluation_" + System.currentTimeMillis(),
                "{}",                  // 额外 JSON 配置
                false,                 // 是否开启多轮对话
                // 流式输出的 OutputStream，SDK 每收到一段 SSE 数据就 write 一次
                new OutputStream() {
                    @Override
                    public void write(int b) {
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        aiResponse.append(new String(b, off, len, StandardCharsets.UTF_8));
                    }
                },
                data -> {
                },                     // 事件回调（未使用）
                agentProperties.getApiKey(),
                agentProperties.getApiSecret(),
                agentProperties.getApiFlowId(),
                fileUrl,               // 传文件 URL（仪态分析等场景）
                parameters             // Agent 模式的结构化参数
        );
        return aiResponse.toString();
    }
}
