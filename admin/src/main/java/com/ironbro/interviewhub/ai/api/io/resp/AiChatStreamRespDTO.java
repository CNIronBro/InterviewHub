package com.ironbro.interviewhub.ai.api.io.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 流式聊天响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatStreamRespDTO {

    /**
     * 内容类型：content / reasoning_content
     */
    private String type;

    /**
     * 内容片段
     */
    private String content;
}
