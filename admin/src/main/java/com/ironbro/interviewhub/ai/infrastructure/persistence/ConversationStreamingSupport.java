package com.ironbro.interviewhub.ai.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话流式输出辅助类
 * TODO: 后续考虑合并到 AiMessageService 中统一管理
 */
@Slf4j
@Component
public class ConversationStreamingSupport {

    /**
     * 缓冲流式输出的文本片段
     */
    public String appendChunk(String currentContent, String chunk) {
        if (chunk == null) {
            return currentContent;
        }
        return (currentContent == null ? "" : currentContent) + chunk;
    }

    /**
     * 检查片段是否为结束标记
     */
    public boolean isTerminal(String chunk) {
        return chunk == null || "[DONE]".equals(chunk.trim());
    }
}
