package com.ironbro.interviewhub.toolkit.xunfei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * AI 内容累积器，用于收集流式响应中的文本和推理内容
 */
@Component
public class AIContentAccumulator {

    private final StringBuilder contentBuilder = new StringBuilder();
    private final StringBuilder reasoningBuilder = new StringBuilder();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void appendChunk(byte[] chunk) {
        try {
            String chunkStr = new String(chunk);
            String jsonStr = chunkStr;
            if (chunkStr.startsWith("data: ")) {
                jsonStr = chunkStr.substring(6);
            }
            jsonStr = jsonStr.trim();
            if (jsonStr.isEmpty() || !jsonStr.startsWith("{")) {
                return;
            }
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode choices = root.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode delta = choice.path("delta");
                    JsonNode content = delta.path("content");
                    if (content.isTextual()) {
                        contentBuilder.append(content.asText());
                    }
                    JsonNode reasoning = delta.path("reasoning_content");
                    if (reasoning.isTextual()) {
                        reasoningBuilder.append(reasoning.asText());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("JSON解析错误: " + e.getMessage());
        }
    }

    public void appendSimpleContent(String content) {
        if (content != null) contentBuilder.append(content);
    }

    public void appendReasoningChunk(byte[] chunk) {
        if (chunk != null) reasoningBuilder.append(new String(chunk));
    }

    public String getFullContent() {
        return contentBuilder.toString();
    }

    public String getFullReasoningContent() {
        return reasoningBuilder.toString();
    }

    public void reset() {
        contentBuilder.setLength(0);
        reasoningBuilder.setLength(0);
    }
}
