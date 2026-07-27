package com.ironbro.interviewhub.media.infrastructure.websocket;

import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.auth.application.WebSocketAuthService;
import com.ironbro.interviewhub.media.infrastructure.integration.XunfeiAudioService;
import com.ironbro.interviewhub.media.infrastructure.integration.XunfeiAudioService.RealtimeTranscriptionUpdate;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时语音转写的产品侧 WebSocket 端点（全局两条 WS 中的第一条）。
 *
 * 职责定位：前端 ↔ 后端的通信入口，负责建连鉴权、控制指令分发、接收二进制音频帧、
 *          回推结构化转写结果。不直接跟讯飞 AST 通信——那条链路在 XunfeiAudioService 里。
 *
 * 为什么依赖注入用 setter + static？因为 JSR 356 @ServerEndpoint 由 WebSocket 容器
 * （Tomcat）为每个连接 new 一个 Handler 实例，不受 Spring 单例管理。必须通过 static
 * 字段 + @Autowired setter 把 Spring Bean 注入进来，onOpen/onMessage 才能用。
 */
@Slf4j
@Component
@ServerEndpoint(value = "/api/xunzhi/v1/xunfei/audio-to-text/{userId}")
public class AudioTranscriptionWebSocketHandler {

    // Spring 管理的依赖，通过 setter 注入到 static 字段，供每个 WS 连接实例使用
    private static volatile XunfeiAudioService xunfeiAudioService;
    private static volatile WebSocketAuthService webSocketAuthService;
    private static volatile ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public void setXunfeiAudioService(XunfeiAudioService service) {
        AudioTranscriptionWebSocketHandler.xunfeiAudioService = service;
    }

    @Autowired
    public void setWebSocketAuthService(WebSocketAuthService service) {
        AudioTranscriptionWebSocketHandler.webSocketAuthService = service;
    }

    @Autowired
    public void setHeartbeatExecutor(@Qualifier("scheduledExecutorService") ScheduledExecutorService scheduledExecutorService) {
        AudioTranscriptionWebSocketHandler.heartbeatExecutor = scheduledExecutorService;
    }

    // 四张 ConcurrentHashMap 管理所有在线会话的运行时状态，全部无锁并发
    private static final ConcurrentMap<String, Session> USER_SESSIONS = new ConcurrentHashMap<>();     // userId → WS 连接
    private static final ConcurrentMap<String, String> SESSION_USER_MAP = new ConcurrentHashMap<>();    // WS sessionId → userId
    private static final ConcurrentMap<String, TranscriptionSessionContext> TRANSCRIPTION_CONTEXTS = new ConcurrentHashMap<>(); // sessionId → 转写上下文
    private static final ConcurrentMap<String, ScheduledFuture<?>> HEARTBEAT_TASKS = new ConcurrentHashMap<>(); // sessionId → 心跳定时任务

    /**
     * 建连三步：鉴权 → 登记映射 → 启动心跳。
     * 鉴权失败直接关闭连接，不建立任何映射。心跳 30s 间隔，用的是独立 ScheduledExecutor。
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        if (!isAuthorizedUser(session, userId)) {
            log.warn("WebSocket auth failed, userId={}, sessionId={}", userId, session.getId());
            closeSession(session, "Unauthorized websocket connection");
            return;
        }

        String sessionId = session.getId();
        USER_SESSIONS.put(userId, session);
        SESSION_USER_MAP.put(sessionId, userId);
        log.info("WebSocket connected, userId={}, sessionId={}", userId, sessionId);

        sendMessage(session, createResponse("connected", "WebSocket connected", userId));
        startHeartbeat(session);
    }

    private boolean isAuthorizedUser(Session session, String pathUserId) {
        if (webSocketAuthService == null) {
            log.error("WebSocketAuthService is not injected, reject websocket connection");
            return false;
        }
        return webSocketAuthService.isAuthorized(session, pathUserId);
    }

    private void closeSession(Session session, String reason) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
            }
        } catch (IOException ex) {
            log.warn("Failed to close websocket session, sessionId={}", session.getId(), ex);
        }
    }

    /**
     * 文本消息 = 控制指令，type 字段区分：ping / start_transcription / stop_transcription / get_status。
     * 纯文本 JSON，不是音频帧。音频走下面的 ByteBuffer 重载。
     */
    @OnMessage
    public void onMessage(Session session, String message) {
        String userId = SESSION_USER_MAP.get(session.getId());
        log.info("Received text message, userId={}, message={}", userId, message);

        try {
            WebSocketMessage wsMessage = JSON.parseObject(message, WebSocketMessage.class);
            handleControlMessage(session, userId, wsMessage);
        } catch (Exception ex) {
            sendMessage(session, createResponse("info", "Received text message: " + message, null));
        }
    }

    /**
     * 二进制消息 = 音频帧。这是生产者-消费者模型的"生产者"端：
     * 前端推 PCM 分片 → 写入当前会话的 Pipe OutputStream → 讯飞发送线程从 Pipe 读端消费。
     * 如果还没有 start_transcription，直接拒绝写入。
     */
    @OnMessage
    public void onMessage(Session session, ByteBuffer byteBuffer) {
        String sessionId = session.getId();
        String userId = SESSION_USER_MAP.get(sessionId);
        log.debug("Received audio chunk, userId={}, sessionId={}, bytes={}",
                userId, sessionId, byteBuffer.remaining());

        try {
            byte[] audioData = new byte[byteBuffer.remaining()];
            byteBuffer.get(audioData);

            TranscriptionSessionContext context = TRANSCRIPTION_CONTEXTS.get(sessionId);
            if (context == null || !context.active.get()) {
                log.warn("Audio chunk received before transcription session started, userId={}, sessionId={}",
                        userId, sessionId);
                sendMessage(session, createResponse("error",
                        "Transcription session is not started. Send start_transcription first.", null));
                return;
            }

            context.audioOutputStream.write(audioData);
            context.audioOutputStream.flush();
        } catch (Exception ex) {
            log.error("Failed to process audio chunk, userId={}, sessionId={}", userId, sessionId, ex);
            sendMessage(session, createResponse("error", "Failed to process audio chunk: " + ex.getMessage(), null));
        }
    }

    /**
     * 断连清理三板斧：停止转写会话 → 取消心跳 → 移除用户映射。
     * 顺序重要：先关 Pipe 断开讯飞链路，再清心跳避免定时任务空转，最后清 mapping。
     */
    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        String userId = SESSION_USER_MAP.get(sessionId);

        stopTranscriptionSession(sessionId);
        cancelHeartbeat(sessionId);

        if (userId != null) {
            USER_SESSIONS.remove(userId);
            SESSION_USER_MAP.remove(sessionId);
        }
        String reason = closeReason != null ? closeReason.getReasonPhrase() : "unknown";
        log.info("WebSocket closed, userId={}, sessionId={}, reason={}",
                userId, sessionId, reason);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        String sessionId = session != null ? session.getId() : null;
        String userId = sessionId != null ? SESSION_USER_MAP.get(sessionId) : null;
        log.error("WebSocket error, userId={}, sessionId={}", userId, sessionId, error);

        if (sessionId != null) {
            stopTranscriptionSession(sessionId);
            cancelHeartbeat(sessionId);
        }
        sendMessage(session, createResponse("error", "WebSocket error: " + error.getMessage(), null));
    }

    private void handleControlMessage(Session session, String userId, WebSocketMessage message) {
        String type = message != null ? message.getType() : null;
        if (type == null) {
            sendMessage(session, createResponse("unknown_command", "Missing command type", null));
            return;
        }

        switch (type) {
            case "ping" -> sendMessage(session, createResponse("pong", "pong", String.valueOf(System.currentTimeMillis())));
            case "start_transcription" -> startTranscriptionSession(session, userId);
            case "stop_transcription" -> {
                boolean stopped = stopTranscriptionSession(session.getId());
                if (stopped) {
                    sendMessage(session, createResponse("transcription_stopped", "Transcription stopped", null));
                } else {
                    sendMessage(session, createResponse("transcription_already_stopped",
                            "Transcription is already stopped", null));
                }
            }
            case "get_status" -> sendMessage(session, createResponse("status", "Connection is healthy", userId));
            default -> sendMessage(session, createResponse("unknown_command", "Unknown command: " + type, null));
        }
    }

    private void startHeartbeat(Session session) {
        if (heartbeatExecutor == null) {
            log.warn("scheduledExecutorService is not injected, skip heartbeat, sessionId={}", session.getId());
            return;
        }
        String sessionId = session.getId();
        ScheduledFuture<?> oldTask = HEARTBEAT_TASKS.remove(sessionId);
        if (oldTask != null) {
            oldTask.cancel(true);
        }

        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (session.isOpen()) {
                sendMessage(session, createResponse("heartbeat", "heartbeat", String.valueOf(System.currentTimeMillis())));
            }
        }, 30, 30, TimeUnit.SECONDS);
        HEARTBEAT_TASKS.put(sessionId, task);
    }

    private void cancelHeartbeat(String sessionId) {
        ScheduledFuture<?> task = HEARTBEAT_TASKS.remove(sessionId);
        if (task != null) {
            task.cancel(true);
        }
    }

    /**
     * 创建转写会话的入口。核心流程：
     * ① 已存在活跃会话 → 拒绝重复 start
     * ② 否则先停掉旧会话 → 创建新 Context（Pipe 对 + 原子标志）→ 调 XunfeiAudioService 建讯飞 WS
     * ③ 并发控制：putIfAbsent 防止两个线程同时创建（只有第一个成功，第二个关闭自己创建的资源）
     */
    private void startTranscriptionSession(Session session, String userId) {
        String sessionId = session.getId();
        TranscriptionSessionContext existing = TRANSCRIPTION_CONTEXTS.get(sessionId);
        if (existing != null && existing.active.get() && !existing.stopRequested.get()) {
            sendMessage(session, createResponse("transcription_already_started",
                    "Transcription is already started", null));
            return;
        }

        stopTranscriptionSession(sessionId);

        TranscriptionSessionContext context = createAndStartTranscriptionSession(session, userId);
        if (context != null) {
            TranscriptionSessionContext raced = TRANSCRIPTION_CONTEXTS.putIfAbsent(sessionId, context);
            if (raced != null && raced.active.get() && !raced.stopRequested.get()) {
                context.active.set(false);
                context.stopRequested.set(true);
                closeQuietly(context.audioOutputStream);
                closeQuietly(context.audioInputStream);
                sendMessage(session, createResponse("transcription_already_started",
                        "Transcription is already started", null));
                return;
            }
            TRANSCRIPTION_CONTEXTS.put(sessionId, context);
            sendMessage(session, createResponse("transcription_started", "Transcription started", null));
        } else {
            sendMessage(session, createResponse("error", "Failed to start transcription", null));
        }
    }

    /**
     * 创建转写上下文并启动讯飞 AST 连接 —— 整个 ASR 链路最核心的方法。
     *
     * 数据流架构（对照看 XunfeiAudioService.realTimeAudioToText）：
     * ① 创建 64KB Piped 流对：audioOutputStream(写) ↔ audioInputStream(读)
     * ② 封装 TranscriptionSessionContext：Pipe + active + stopRequested + lastUpdate
     * ③ 调 realTimeAudioToText(audioInputStream, callback) —— 注意传的是读端！
     *    XunfeiAudioService 从 audioInputStream 消费音频，不直接拿前端 ByteBuffer
     * ④ callback 每收到一条增量识别结果就立刻推前端（transcription 事件）
     * ⑤ whenComplete：转写完成后的收尾，区分正常 final / 主动 stop / 异常 error
     *    - 主动 stop 不补 final（final = 讯飞自己说做完了，不是用户说停）
     *    - Pipe closed 异常属于预期行为（stop 时主动关闭），不报 error
     */
    private TranscriptionSessionContext createAndStartTranscriptionSession(Session session, String userId) {
        String sessionId = session.getId();
        try {
            if (xunfeiAudioService == null) {
                log.error("XunfeiAudioService is not injected yet, cannot start transcription. sessionId={}", sessionId);
                return null;
            }
            PipedInputStream audioInputStream = new PipedInputStream(64 * 1024);
            PipedOutputStream audioOutputStream = new PipedOutputStream(audioInputStream);
            AtomicBoolean active = new AtomicBoolean(true);
            TranscriptionSessionContext context = new TranscriptionSessionContext(audioInputStream, audioOutputStream, active);

            CompletableFuture<String> future = xunfeiAudioService.realTimeAudioToText(audioInputStream, update ->
                    {
                        context.lastUpdate.set(update);
                        sendMessage(session, createResponse("transcription", "Partial snapshot", update, true));
                    }
            );

            future.whenComplete((finalResult, throwable) -> {
                if (throwable != null && !isExpectedStopException(context, throwable)) {
                    log.error("Transcription failed, userId={}, sessionId={}", userId, sessionId, throwable);
                    sendMessage(session, createResponse("error", "Transcription failed: " + throwable.getMessage(), null));
                } else {
                    log.info("Transcription finished, userId={}, sessionId={}", userId, sessionId);
                    if (!context.stopRequested.get() && finalResult != null) {
                        sendMessage(session, createResponse("final", "Transcription completed",
                                buildFinalUpdate(finalResult, context.lastUpdate.get()), true));
                    }
                }
                cleanupTranscriptionContext(sessionId, context);
            });
            return context;
        } catch (Exception ex) {
            log.error("Failed to create transcription session, userId={}, sessionId={}", userId, sessionId, ex);
            return null;
        }
    }

    private boolean stopTranscriptionSession(String sessionId) {
        TranscriptionSessionContext context = TRANSCRIPTION_CONTEXTS.remove(sessionId);
        if (context == null) {
            return false;
        }
        context.active.set(false);
        context.stopRequested.set(true);
        closeQuietly(context.audioOutputStream);
        return true;
    }

    private void cleanupTranscriptionContext(String sessionId, TranscriptionSessionContext context) {
        TRANSCRIPTION_CONTEXTS.remove(sessionId, context);
        context.active.set(false);
        closeQuietly(context.audioOutputStream);
        closeQuietly(context.audioInputStream);
    }

    /**
     * 区分"用户主动停止导致的 Pipe closed"和"真的出错了"。
     * 只有 stopRequested=true 且异常信息包含 "Pipe closed" 或 "Stream closed"
     * 才算预期异常——这是 stopTranscriptionSession 关 OutputStream 的正常连锁反应。
     */
    private boolean isExpectedStopException(TranscriptionSessionContext context, Throwable throwable) {
        if (!context.stopRequested.get()) {
            return false;
        }
        Throwable cursor = throwable;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && (msg.contains("Pipe closed") || msg.contains("Stream closed"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    private void sendMessage(Session session, String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException ex) {
                log.error("Failed to send message, sessionId={}", session.getId(), ex);
            }
        }
    }

    public static void sendMessageToUser(String userId, String type, String message, String data) {
        Session session = USER_SESSIONS.get(userId);
        if (session == null || !session.isOpen()) {
            log.warn("User is offline, userId={}", userId);
            return;
        }
        try {
            session.getBasicRemote().sendText(createStaticResponse(type, message, data));
        } catch (IOException ex) {
            log.error("Failed to send message to user, userId={}", userId, ex);
        }
    }

    public static void broadcastMessage(String type, String message, String data) {
        String payload = createStaticResponse(type, message, data);
        USER_SESSIONS.forEach((userId, session) -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(payload);
                } catch (IOException ex) {
                    log.error("Broadcast failed, userId={}", userId, ex);
                }
            }
        });
    }

    public static Set<String> getOnlineUsers() {
        return USER_SESSIONS.keySet();
    }

    public static boolean isUserOnline(String userId) {
        Session session = USER_SESSIONS.get(userId);
        return session != null && session.isOpen();
    }

    private String createResponse(String type, String message, String data) {
        return createResponse(type, message, data, false);
    }

    private String createResponse(String type, String message, String data, boolean isSnapshot) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(data);
        response.setFullText(resolveFullText(type, data));
        response.setIsSnapshot(isSnapshot);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        return JSON.toJSONString(response);
    }

    private String createResponse(String type,
                                  String message,
                                  RealtimeTranscriptionUpdate update,
                                  boolean isSnapshot) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(update != null ? update.fullText() : null);
        response.setFullText(update != null ? update.fullText() : null);
        response.setDisplayText(update != null ? update.displayText() : null);
        response.setCommittedText(update != null ? update.committedText() : null);
        response.setLiveText(update != null ? update.liveText() : null);
        response.setRevision(update != null ? update.revision() : null);
        response.setResultStatus(update != null ? update.resultStatus() : null);
        response.setIsSnapshot(isSnapshot);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        if (update != null) {
            response.setSegmentId(update.segmentId());
            response.setSentenceSeq(update.segmentId());
            response.setSegmentText(update.segmentText());
            response.setPgs(update.pgs());
            response.setRg(update.rg());
            response.setBg(update.bg());
            response.setEd(update.ed());
            response.setIsFinalPacket(update.finalPacket());
        }
        return JSON.toJSONString(response);
    }

    private RealtimeTranscriptionUpdate buildFinalUpdate(String finalResult,
                                                         RealtimeTranscriptionUpdate lastUpdate) {
        if (lastUpdate == null) {
            return new RealtimeTranscriptionUpdate(
                    finalResult,
                    finalResult,
                    "",
                    finalResult,
                    1,
                    "final",
                    0,
                    finalResult,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
        return new RealtimeTranscriptionUpdate(
                finalResult,
                finalResult,
                "",
                finalResult,
                lastUpdate.revision() != null ? lastUpdate.revision() + 1 : 1,
                "final",
                lastUpdate.segmentId(),
                lastUpdate.segmentText(),
                lastUpdate.pgs(),
                lastUpdate.rg(),
                lastUpdate.bg(),
                lastUpdate.ed(),
                true
        );
    }

    private static String createStaticResponse(String type, String message, String data) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(data);
        response.setFullText(resolveFullText(type, data));
        response.setIsSnapshot(false);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        return JSON.toJSONString(response);
    }

    private static String resolveFullText(String type, String data) {
        if ("transcription".equals(type) || "final".equals(type)) {
            return data;
        }
        return null;
    }

    private static String resolveUpdateAction(String type) {
        if ("transcription".equals(type)) {
            return "replace";
        }
        if ("final".equals(type)) {
            return "archive";
        }
        return "none";
    }

    /**
     * 推送给前端的结构化响应。
     *
     * 三级文本渲染（核心面试点）：
     * - displayText = 前端实际展示的完整文本（committedText + liveText）
     * - committedText = 已稳定不会变的部分（深色/正常字体）
     * - liveText = 仍在滚动修正的尾部（浅色/斜体，暗示可能还会变）
     * - fullText = 全量快照，通常等于 displayText
     * - revision = 递增版本号，每次更新 +1
     * - updateAction = "replace"（transcription）或 "archive"（final）
     *
     * 讯飞元信息（透传给前端做精确渲染）：
     * - segmentId / pgs / rg / bg / ed / isFinalPacket
     */
    @Data
    public static class WebSocketResponse {
        private String type;
        private String message;
        private String data;
        // 三级文本渲染的核心字段
        private String fullText;
        private String displayText;
        private String committedText;
        private String liveText;
        private Integer revision;
        private String resultStatus;
        private Boolean isSnapshot;
        private String updateAction;
        private Long timestamp;
        // 讯飞 AST 分段元信息，透传供前端精确渲染
        private Integer segmentId;
        private Integer sentenceSeq;
        private String segmentText;
        private String pgs;
        private int[] rg;
        private Integer bg;
        private Integer ed;
        private Boolean isFinalPacket;
    }

    @Data
    public static class WebSocketMessage {
        private String type;
    }

    /**
     * 转写会话上下文 —— 借鉴 NIO Buffer 异步缓冲思想。
     *
     * audioOutputStream/audioInputStream: 一对 64KB Piped 流，构成生产者-消费者模型的管道。
     *   前端 WS 线程（生产者）写 audioOutputStream → 讯飞发送线程（消费者）读 audioInputStream。
     *   数据不经过中间 byte[] 拷贝，两端共享同一块管道缓冲区。速率不匹配时自动背压。
     *
     * active:      标记转写是否活跃，onMessage 音频帧时先检查
     * stopRequested: 用户是否主动要求停止，决定 whenComplete 中发不发 final
     * lastUpdate:  最新一次增量识别快照，final 构建时继承它的分段元信息
     */
    private static class TranscriptionSessionContext {
        private final PipedInputStream audioInputStream;
        private final PipedOutputStream audioOutputStream;
        private final AtomicBoolean active;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicReference<RealtimeTranscriptionUpdate> lastUpdate = new AtomicReference<>();

        private TranscriptionSessionContext(PipedInputStream audioInputStream,
                                            PipedOutputStream audioOutputStream,
                                            AtomicBoolean active) {
            this.audioInputStream = audioInputStream;
            this.audioOutputStream = audioOutputStream;
            this.active = active;
        }
    }
}
