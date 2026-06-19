package com.ironbro.interviewhub.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 面试 AI 的统一调用入口（骨架版）
 * TODO: 后续接入 SingleFlight 去重和 Guard 限流熔断
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAiInvoker {

    private final XingChenAIClient xingChenAIClient;

    public String callAiSync(String prompt, String sessionId, AgentPropertiesDO agentProperties) throws Exception {
        return doChat(prompt, sessionId, agentProperties, null, null);
    }

    public String callAiSyncWithFile(String prompt, String sessionId, AgentPropertiesDO agentProperties, String fileUrl)
            throws Exception {
        return doChat(prompt, sessionId, agentProperties, fileUrl, null);
    }

    public String callAiSyncWithParameters(String sessionId, AgentPropertiesDO agentProperties,
                                            Map<String, Object> parameters) throws Exception {
        String input = parameters == null ? "" : StrUtil.blankToDefault(
                String.valueOf(parameters.getOrDefault("AGENT_USER_INPUT", "")), "");
        return doChat(input, sessionId, agentProperties, null, parameters);
    }

    private String doChat(String input, String sessionId, AgentPropertiesDO agentProperties,
                          String fileUrl, Map<String, Object> parameters) throws Exception {
        StringBuilder aiResponse = new StringBuilder();
        xingChenAIClient.chat(
                input,
                StrUtil.isNotBlank(sessionId) ? sessionId : "eval_" + System.currentTimeMillis(),
                "{}", false,
                new OutputStream() {
                    @Override public void write(int b) {}
                    @Override public void write(byte[] b, int off, int len) {
                        aiResponse.append(new String(b, off, len, StandardCharsets.UTF_8));
                    }
                },
                data -> {},
                agentProperties.getApiKey(), agentProperties.getApiSecret(),
                agentProperties.getApiFlowId(), fileUrl, parameters);
        return aiResponse.toString();
    }
}