package com.ironbro.interviewhub.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one JSON object per AI invocation to log/{sessionId}.jsonl.
 * Trace failures never interrupt the interview.
 */
@Component
@Slf4j
public class InterviewAiTraceLogger {

    private final Path traceDirectory;
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public InterviewAiTraceLogger(
            @Value("${interview.ai-trace.directory:}") String configuredDirectory) {
        traceDirectory = resolveTraceDirectory(configuredDirectory);
    }

    public void record(
            String sessionId,
            String stage,
            AgentPropertiesDO agent,
            String input,
            String fileUrl,
            Map<String, Object> parameters,
            String response,
            long durationMillis,
            Throwable error) {
        String safeSessionId = safeSessionId(sessionId);
        try {
            Files.createDirectories(traceDirectory);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
            entry.put("sequence", sequences
                    .computeIfAbsent(safeSessionId, ignored -> new AtomicLong())
                    .incrementAndGet());
            entry.put("sessionId", sessionId);
            entry.put("eventType", resolveEventType(stage, parameters));
            entry.put("eventLabel", resolveEventLabel(stage, parameters));
            entry.put("stage", stage);
            entry.put("agentName", agent == null ? null : agent.getAgentName());
            entry.put("flowId", agent == null ? null : agent.getApiFlowId());
            entry.put("durationMillis", durationMillis);
            entry.put("success", error == null);
            entry.put("request", requestPayload(input, fileUrl, parameters));
            entry.put("modelRawResponse", response);
            entry.put("modelResponseJson", parseJsonIfPossible(response));
            if (error != null) {
                entry.put("errorType", error.getClass().getName());
                entry.put("errorMessage", error.getMessage());
            }
            append(safeSessionId, JSON.toJSONString(entry));
        } catch (Exception traceException) {
            log.warn("Failed to write interview AI trace, sessionId={}, stage={}, reason={}",
                    sessionId, stage, traceException.getMessage());
        }
    }

    public Path getTraceDirectory() {
        return traceDirectory;
    }

    private Map<String, Object> requestPayload(
            String input, String fileUrl, Map<String, Object> parameters) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", input);
        request.put("fileUrl", fileUrl);
        request.put("parameters", parameters == null ? Map.of() : parameters);
        return request;
    }

    private Object parseJsonIfPossible(String response) {
        if (StrUtil.isBlank(response)) return null;
        try {
            return JSON.parse(response);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void append(String safeSessionId, String jsonLine) throws Exception {
        Path file = traceDirectory.resolve(safeSessionId + ".jsonl");
        Object lock = fileLocks.computeIfAbsent(safeSessionId, ignored -> new Object());
        synchronized (lock) {
            Files.writeString(
                    file,
                    jsonLine + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }

    private String resolveEventType(String stage, Map<String, Object> parameters) {
        if (InterviewAiGuardStage.RESUME_PROFILE_EXTRACTION.equals(stage)) return "RESUME_PROFILE";
        if (InterviewAiGuardStage.INTERVIEW_EXTRACTION.equals(stage)) return "NORMAL_QUESTION";
        if (InterviewAiGuardStage.INTERVIEW_FOLLOWUP.equals(stage)
                || "FOLLOW_UP".equalsIgnoreCase(stringParameter(parameters, "mode"))) {
            return "FOLLOW_UP";
        }
        if (InterviewAiGuardStage.INTERVIEW_REVIEW.equals(stage)) return "SCORE_FEEDBACK_REVIEW";
        if (InterviewAiGuardStage.INTERVIEW_EVALUATION.equals(stage)) return "SCORE_FEEDBACK";
        if (InterviewAiGuardStage.INTERVIEW_DEMEANOR.equals(stage)) return "DEMEANOR_ANALYSIS";
        return "AI_RESPONSE";
    }

    private String resolveEventLabel(String stage, Map<String, Object> parameters) {
        return switch (resolveEventType(stage, parameters)) {
            case "RESUME_PROFILE" -> "简历解析与评分";
            case "NORMAL_QUESTION" -> "正常提问（初始题单生成）";
            case "FOLLOW_UP" -> "追问生成";
            case "SCORE_FEEDBACK_REVIEW" -> "Score Feedback（二次复核）";
            case "SCORE_FEEDBACK" -> "Score Feedback（答案评分）";
            case "DEMEANOR_ANALYSIS" -> "仪态分析";
            default -> "大模型响应";
        };
    }

    private String stringParameter(Map<String, Object> parameters, String key) {
        Object value = parameters == null ? null : parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String safeSessionId(String sessionId) {
        String value = StrUtil.blankToDefault(sessionId, "no-session");
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return StrUtil.blankToDefault(safe, "no-session");
    }

    private Path resolveTraceDirectory(String configuredDirectory) {
        if (StrUtil.isNotBlank(configuredDirectory)) {
            return Path.of(configuredDirectory).toAbsolutePath().normalize();
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("admin"))) {
            return workingDirectory.resolve("log");
        }
        Path parent = workingDirectory.getParent();
        if ("admin".equalsIgnoreCase(String.valueOf(workingDirectory.getFileName()))
                && parent != null) {
            return parent.resolve("log");
        }
        return workingDirectory.resolve("log");
    }
}
