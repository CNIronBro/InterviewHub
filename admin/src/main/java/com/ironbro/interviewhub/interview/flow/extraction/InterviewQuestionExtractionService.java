package com.ironbro.interviewhub.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.api.io.req.InterviewQuestionReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewQuestionRespDTO;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardException;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardStage;
import com.ironbro.interviewhub.interview.application.guard.lock.InterviewAiSessionLockService;
import com.ironbro.interviewhub.interview.shared.InterviewAiInvoker;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import com.ironbro.interviewhub.interview.service.InterviewQuestionCacheService;
import com.ironbro.interviewhub.interview.service.InterviewQuestionService;
import com.ironbro.interviewhub.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionExtractionService {

    /** 提取面试题的 prompt，要求 AI 返回 JSON 格式（questions/sugest/type/resumeScore） */
    private static final String EXTRACTION_PROMPT =
            "Extract technical interview questions from the uploaded resume. "
                    + "Return JSON only with keys questions, sugest, type, and resumeScore. "
                    + "Do not output smallTalk, greetings, or fallback chat content.";

    // 根据业务场景解析对应的 AI Agent 配置（apiKey/secret/flowId）
    private final BusinessAgentResolver businessAgentResolver;
    // 讯星辰 SDK 客户端，负责上传文件和调用大模型
    private final XingChenAIClient xingChenAIClient;
    // AI 调用统一入口（去重 → 限流 → 调模型）
    private final InterviewAiInvoker interviewAiInvoker;
    // 会话级分布式重锁，防止同一会话并发执行提取等重操作
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    // DB 操作：interview_question 表
    private final InterviewQuestionService interviewQuestionService;
    // Redis 缓存：面试题、分数、建议等
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    // AI 响应解析器：从大模型返回的 JSON 中提取结构化字段
    private final InterviewResponseParser interviewResponseParser;

    /**
     * 上传简历 → AI 解析 → 提取面试题 → 落库 + 缓存。
     * 流程：①解析Agent配置 → ②加分布式锁 → ③上传OSS → ④调AI → ⑤原始响应落DB → ⑥⑦⑧结构化解析+缓存+二次落库 → ⑨释放锁
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());

        // ① 根据业务场景"面试题提取"，从 DB/缓存中解析出对应的 AI Agent 配置（apiKey、apiSecret、flowId 等）
        //    resolveRequired 找不到配置会直接抛异常，保证后续流程不会空指针
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(
                BusinessAgentScene.INTERVIEW_QUESTION_EXTRACTION);
        reqDTO.setAgentId(agentProperties.getId()); // 记录使用的 Agent ID，后续落库需要
        response.setIsSuccess(0); // 默认失败，成功时再改为 1

        RLock heavyLock = null;
        long startTime = System.currentTimeMillis();
        try {
            // ② 加会话级重锁，同一 session 同时只能有一个提取任务在跑
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            // ③ 上传简历 PDF 到讯星辰平台，拿到文件 URL
            String fileUrl = uploadResumeIfPresent(reqDTO, agentProperties, response);
            if (fileUrl == null) {
                return response;
            }

            // ④ 调 AI 解析简历（SingleFlight key = stage|sessionId|fileUrl，同一简历不会重复解析）
            String fullContent = interviewAiInvoker.callAiSyncWithFile(
                    EXTRACTION_PROMPT,
                    reqDTO.getSessionId(),
                    agentProperties,
                    fileUrl,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EXTRACTION, reqDTO.getSessionId(), fileUrl)
            );

            long responseTime = System.currentTimeMillis() - startTime;
            reqDTO.setResumeFileUrl(fileUrl);

            // ⑤ 先持久化原始响应到 DB，即使后续解析失败，原始数据还在
            persistRawResponse(reqDTO, fullContent, responseTime);

            response.setResumeFileUrl(fileUrl);
            response.setResponseTime((int) responseTime);

            // ⑥⑦⑧ 结构化解析 + Redis 缓存 + DB 二次落库
            if (!populateStructuredResponse(reqDTO, response, fullContent)) {
                return response;
            }

            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}", reqDTO.getSessionId());
            return response;
        } catch (InterviewAiGuardException e) {
            // 限流/熔断异常：失败也落库，记录错误信息方便排障
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Interview question extraction guarded failure, sessionId={}, code={}, message={}",
                    reqDTO.getSessionId(), e.getErrorCode(), e.getMessage());
            try {
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction guard error record: {}", saveException.getMessage());
            }
            response.setErrorMessage(e.getMessage());
            response.setIsSuccess(0);
            return response;
        } catch (Exception e) {
            // 其他异常：同样落库记录
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed: {}", e.getMessage(), e);
            try {
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction error record: {}", saveException.getMessage());
            }

            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            // ⑨ 无论成功失败，最终释放锁
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    /** 上传简历 PDF 到讯星辰平台，返回文件 URL；文件为空则返回 null 并设置错误信息 */
    private String uploadResumeIfPresent(
            InterviewQuestionReqDTO reqDTO,
            AgentPropertiesDO agentProperties,
            InterviewQuestionRespDTO response) {
        if (reqDTO.getResumePdf() == null || reqDTO.getResumePdf().isEmpty()) {
            response.setErrorMessage("resume file does not exist");
            return null;
        }
        try {
            String fileUrl = xingChenAIClient.uploadFile(
                    reqDTO.getResumePdf(),
                    agentProperties.getApiKey(),
                    agentProperties.getApiSecret()
            );
            log.info("Resume uploaded successfully, url={}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("Resume upload failed: {}", e.getMessage());
            response.setErrorMessage("failed to upload resume file");
            return null;
        }
    }

    /** 持久化 AI 原始响应到 DB，先落库再解析，保证原始数据不丢失 */
    private void persistRawResponse(InterviewQuestionReqDTO reqDTO, String fullContent, long responseTime) {
        try {
            interviewQuestionService.createFromAIResponse(
                    reqDTO,
                    fullContent,
                    (int) responseTime,
                    null
            );
            log.info("Interview question response saved, sessionId={}", reqDTO.getSessionId());
        } catch (Exception e) {
            log.error("Failed to save interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage());
        }
    }

    /** 结构化解析 AI 响应 → 缓存到 Redis → 二次落 DB（Redis 丢失后的恢复来源） */
    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent) {
        try {
            log.info("Start parsing interview question response, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    fullContent == null ? 0 : fullContent.length(),
                    digestForLog(fullContent));

            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(fullContent);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                response.setErrorMessage(workflowErrorMessage);
                log.warn("Interview question workflow returned error, sessionId={}, message={}",
                        reqDTO.getSessionId(), workflowErrorMessage);
                return false;
            }

            String extractedContent = interviewResponseParser.extractContentFromInterviewResponse(fullContent);
            log.info("Extracted interview content summary, sessionId={}, contentLength={}, contentHash={}",
                    reqDTO.getSessionId(),
                    extractedContent == null ? 0 : extractedContent.length(),
                    digestForLog(extractedContent));
            if (StrUtil.isBlank(extractedContent)) {
                response.setErrorMessage("interview question response content is blank");
                return false;
            }

            Map<String, Object> responseMap = interviewResponseParser.extractStructuredResult(
                    extractedContent,
                    "questions",
                    "sugest",
                    "suggestions",
                    "resumeScore",
                    "type",
                    "smallTalk"
            );
            if (responseMap == null || responseMap.isEmpty()) {
                response.setErrorMessage("interview question response parse failed");
                log.warn("Interview question response parse failed, responseMap is null");
                return false;
            }

            log.info("Interview question response fields: {}", responseMap.keySet());
            Map<String, Object> resumeContext = buildResumeContext(responseMap);
            if (!resumeContext.isEmpty()) {
                interviewQuestionCacheService.cacheResumeContext(reqDTO.getSessionId(), resumeContext);
            }

            List<String> questions = normalizeStringList(responseMap.get("questions"));
            if (questions.isEmpty()) {
                String smallTalk = interviewResponseParser.asString(responseMap.get("smallTalk"));
                response.setErrorMessage(StrUtil.isNotBlank(smallTalk)
                        ? "workflow fell back to smallTalk instead of interview questions"
                        : "workflow returned empty interview questions");
                log.warn("Interview question extraction returned no questions, sessionId={}, smallTalk={}",
                        reqDTO.getSessionId(), smallTalk);
                return false;
            }

            interviewQuestionCacheService.cacheInterviewQuestions(reqDTO.getSessionId(), questions);
            Map<String, String> questionMap =
                    interviewQuestionCacheService.getSessionInterviewQuestions(reqDTO.getSessionId());
            response.setQuestions(questionMap);
            response.setQuestionCount(questions.size());
            interviewQuestionCacheService.initInterviewFlow(reqDTO.getSessionId(), questions.size());

            List<String> suggestions = normalizeSuggestions(responseMap);
            if (!suggestions.isEmpty()) {
                interviewQuestionCacheService.cacheInterviewSuggestions(reqDTO.getSessionId(), suggestions);
                Map<String, String> suggestionMap =
                        interviewQuestionCacheService.getSessionInterviewSuggestions(reqDTO.getSessionId());
                response.setSuggestions(suggestionMap);
                response.setSuggestionCount(suggestions.size());
            } else {
                log.warn("Interview question response does not contain suggestions");
            }

            // type 字段兼容历史别名，保证 interviewDirection/interviewType 在不同模型输出下都能回补。
            String interviewType = interviewResponseParser.asString(responseMap.get("type"));
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewType"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("direction"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewDirection"));
            }
            if (StrUtil.isNotBlank(interviewType)) {
                interviewQuestionCacheService.cacheInterviewDirection(reqDTO.getSessionId(), interviewType);
                response.setInterviewType(interviewType);
            } else {
                log.warn("Interview question response does not contain type field");
            }

            Integer resumeScore = interviewResponseParser.parseScoreFromResponse(responseMap, "resumeScore");
            if (resumeScore != null) {
                interviewQuestionCacheService.cacheResumeScore(reqDTO.getSessionId(), resumeScore);
                response.setResumeScore(resumeScore);
            } else {
                log.warn("Interview question response does not contain valid resumeScore field");
            }

            // 结构化二次落库用于 Redis 丢失后的恢复来源，避免报告阶段出现字段缺失。
            persistStructuredFields(reqDTO, questions, suggestions, resumeScore, interviewType, resumeContext);
            interviewQuestionCacheService.resetSessionScore(reqDTO.getSessionId());
            log.info("Session score reset, sessionId={}", reqDTO.getSessionId());
            return true;
        } catch (Exception cacheException) {
            response.setErrorMessage("failed to parse interview question response");
            log.error(
                    "Failed to cache interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(),
                    cacheException.getMessage()
            );
            return false;
        }
    }

    /** 从 AI 响应中提取建议列表，优先取 sugest（AI 常见拼写错误），没有则取 suggestions */
    private List<String> normalizeSuggestions(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> suggestions = normalizeStringList(responseMap.get("sugest"));
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        return normalizeStringList(responseMap.get("suggestions"));
    }

    /** 将 AI 返回的值统一转为字符串列表（兼容数组/逗号分隔字符串等格式） */
    private List<String> normalizeStringList(Object value) {
        return interviewResponseParser.asStringList(value);
    }

    /** 取 SHA256 前 16 位用于日志，避免打印大段响应内容 */
    private String digestForLog(String value) {
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        return DigestUtil.sha256Hex(value).substring(0, 16);
    }

    /** 将结构化字段（questions/suggestions/score/type/context）二次落 DB，Redis 丢失后可从此恢复 */
    private void persistStructuredFields(
            InterviewQuestionReqDTO reqDTO,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext) {
        try {
            interviewQuestionService.upsertStructuredExtraction(
                    reqDTO.getSessionId(),
                    reqDTO.getUserName(),
                    reqDTO.getAgentId(),
                    reqDTO.getResumeFileUrl(),
                    questions,
                    suggestions,
                    resumeScore,
                    interviewType,
                    resumeContext
            );
        } catch (Exception ex) {
            log.warn("Failed to persist structured extraction fields, sessionId={}, error={}",
                    reqDTO.getSessionId(), ex.getMessage(), ex);
        }
    }

    /** 从 AI 响应中提取简历上下文（排除 questions/suggestions），用于后续评分时作为参考背景 */
    private Map<String, Object> buildResumeContext(Map<String, Object> responseMap) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (responseMap == null || responseMap.isEmpty()) {
            return context;
        }
        for (Map.Entry<String, Object> entry : responseMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("questions".equals(key) || "sugest".equals(key) || "suggestions".equals(key)) {
                continue;
            }
            context.put(key, value);
        }
        return context;
    }
}
