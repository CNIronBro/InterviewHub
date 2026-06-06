package com.ironbro.interviewhub.ai.service.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.ai.api.io.resp.AiChatStreamRespDTO;
import com.ironbro.interviewhub.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.ironbro.interviewhub.ai.dao.entity.AiPropertiesDO;
import com.ironbro.interviewhub.ai.enums.AiPropritiesType;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import com.ironbro.interviewhub.toolkit.xunfei.AIContentAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 通用 AI 聊天处理器，基于 Spring AI 实现
 * 支持 OpenAI、DeepSeek、Doubao 等兼容 OpenAI 接口的模型
 */
@Slf4j
@Component
public class UniversalAiChatHandler implements AiChatHandler {

    @Override
    public String getType() {
        return "universal";
    }

    public boolean supports(String type) {
        return AiPropritiesType.isSupported(type);
    }

    @Override
    public void streamToSink(AiPropertiesDO aiProperties, String userMessage,
                             List<AiMessageHistoryRespDTO> historyMessages,
                             FluxSink<String> sink, AIContentAccumulator accumulator) throws Exception {
        ChatClient chatClient = createChatClient(aiProperties);
        List<Message> messages = buildMessages(aiProperties, userMessage, historyMessages);

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] streamError = new Throwable[1];

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .subscribe(
                        chatResponse -> {
                            try {
                                Generation generation = chatResponse.getResult();
                                if (generation != null) {
                                    String content = generation.getOutput().getText();
                                    if (StrUtil.isNotEmpty(content)) {
                                        AiChatStreamRespDTO resp = AiChatStreamRespDTO.builder()
                                                .type("content").content(content).build();
                                        sink.next(JSON.toJSONString(resp));
                                        accumulator.appendSimpleContent(content);
                                    }
                                    // 尝试获取 reasoning_content（DeepSeek 深度思考）
                                    try {
                                        Object reasoningObj = generation.getOutput().getMetadata().get("reasoningContent");
                                        if (reasoningObj != null) {
                                            String reasoning = reasoningObj.toString();
                                            if (StrUtil.isNotEmpty(reasoning)) {
                                                AiChatStreamRespDTO resp = AiChatStreamRespDTO.builder()
                                                        .type("reasoning_content").content(reasoning).build();
                                                sink.next(JSON.toJSONString(resp));
                                                accumulator.appendReasoningChunk(reasoning.getBytes());
                                            }
                                        }
                                    } catch (Exception ignore) {
                                    }
                                }
                            } catch (Exception e) {
                                log.error("流式响应处理错误", e);
                                streamError[0] = e;
                                sink.error(e);
                                latch.countDown();
                            }
                        },
                        error -> {
                            log.error("流式响应发生错误", error);
                            streamError[0] = error;
                            sink.error(error);
                            latch.countDown();
                        },
                        latch::countDown
                );

        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new RuntimeException("AI 响应超时");
        }
        if (streamError[0] != null) {
            throw new RuntimeException(streamError[0]);
        }
    }

    private ChatClient createChatClient(AiPropertiesDO aiProperties) {
        String baseUrl = aiProperties.getApiUrl();
        String apiKey = aiProperties.getApiKey();

        if (StrUtil.isBlank(baseUrl)) {
            baseUrl = AiPropritiesType.getByType(aiProperties.getAiType()).getDefaultBaseUrl();
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new ClientException("AI API Key 未配置");
        }

        RestClient.Builder restClientBuilder = RestClient.builder();

        OpenAiApi openAiApi = new OpenAiApi(
                baseUrl, new SimpleApiKey(apiKey),
                new org.springframework.util.LinkedMultiValueMap<>(),
                "/chat/completions", "/embeddings",
                restClientBuilder, WebClient.builder(),
                new org.springframework.web.client.DefaultResponseErrorHandler()
        );

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(aiProperties.getModelName());
        if (aiProperties.getMaxTokens() != null) {
            optionsBuilder.maxTokens(aiProperties.getMaxTokens());
        }
        OpenAiChatOptions options = optionsBuilder.build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        OpenAiChatModel chatModel = new OpenAiChatModel(
                openAiApi, options, toolCallingManager,
                RetryTemplate.defaultInstance(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );

        return ChatClient.builder(chatModel).defaultOptions(options).build();
    }

    private List<Message> buildMessages(AiPropertiesDO aiProperties, String userMessage,
                                        List<AiMessageHistoryRespDTO> historyMessages) {
        List<Message> messages = new ArrayList<>();

        if (StrUtil.isNotBlank(aiProperties.getSystemPrompt())) {
            messages.add(new SystemMessage(aiProperties.getSystemPrompt()));
        }

        if (CollUtil.isNotEmpty(historyMessages)) {
            for (AiMessageHistoryRespDTO history : historyMessages) {
                String content = history.getMessageContent();
                if (history.getMessageType() != null && history.getMessageType() == 1) {
                    messages.add(new UserMessage(content));
                } else {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }
}
