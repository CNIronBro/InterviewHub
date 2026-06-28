package com.ironbro.interviewhub.media.infrastructure.integration;

import cn.hutool.core.util.StrUtil;
import cn.xfyun.api.IatClient;
import cn.xfyun.model.response.iat.IatResponse;
import cn.xfyun.model.response.iat.IatResult;
import cn.xfyun.model.response.iat.Text;
import cn.xfyun.service.iat.AbstractIatWebSocketListener;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ironbro.interviewhub.common.config.storage.ApplicationStorageProperties;
import com.ironbro.interviewhub.common.config.xunfei.XunfeiLatProperties;
import com.ironbro.interviewhub.common.convention.exception.ClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.commons.codec.binary.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【面试重点】讯飞语音服务 —— 全局两条 WebSocket 中的第二条（后端 → 讯飞 AST）。
 *
 * 两种模式：
 * - convertAudioToText：文件模式，IatClient 上传完整 PCM 文件，适用于非实时场景
 * - realTimeAudioToText：实时流式模式，从 Pipe 读端消费音频推给讯飞 AST，
 *   接收增量修正包交给 AstTranscriptionAssembler 做分段去重装配
 *
 * 关键架构认识：
 * - 这个类不直接拿前端的 ByteBuffer。它消费 TranscriptionSessionContext 里 Pipe 的 audioInputStream
 * - 它是"消费者"端：Pipe 那边是前端 WS 线程写入，这边是讯飞发送线程读取，速率自动解耦
 * - OkHttpClient 设置了 20s ping interval，保活后端到讯飞这条 WS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XunfeiAudioService {

    // OkHttp WebSocket 客户端，20s 心跳保活讯飞 AST 连接
    private static final OkHttpClient WS_CLIENT = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private static final String AST_WS_BASE_URL = "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1";
    // 讯飞 AST 每次发送音频块的大小（1280字节），不是随便写的，是讯飞协议规范
    private static final int CHUNK_SIZE_BYTES = 1280;

    private final XunfeiLatProperties xunfeiLatPropertiesConfig;
    private final ApplicationStorageProperties storageProperties;
    @Resource(name = "queryExecutor")
    private ExecutorService queryExecutor;

    // 旧版 IatClient（文件模式），init() 中初始化，与实时 AST 无关
    private IatClient iatClient;

    @PostConstruct
    public void init() {
        try {
            iatClient = new IatClient.Builder()
                    .signature(
                            normalize(xunfeiLatPropertiesConfig.getAppId()),
                            normalize(xunfeiLatPropertiesConfig.getApiKey()),
                            normalize(xunfeiLatPropertiesConfig.getApiSecret())
                    )
                    .dwa("wpgs")
                    .build();

            log.info("Xunfei audio service initialized, appId={}, apiKeyLen={}, apiSecretLen={}",
                    mask(normalize(xunfeiLatPropertiesConfig.getAppId())),
                    lengthOf(normalize(xunfeiLatPropertiesConfig.getApiKey())),
                    lengthOf(normalize(xunfeiLatPropertiesConfig.getApiSecret())));
        } catch (Exception ex) {
            log.error("Failed to initialize Xunfei audio service", ex);
            throw new RuntimeException("Failed to initialize Xunfei audio service", ex);
        }
    }

    public CompletableFuture<String> convertAudioToText(MultipartFile audioFile) {
        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);
        List<Text> resultSegments = new ArrayList<>();

        AbstractIatWebSocketListener listener = new AbstractIatWebSocketListener() {
            @Override
            public void onSuccess(WebSocket webSocket, IatResponse iatResponse) {
                if (iatResponse.getCode() != 0) {
                    future.completeExceptionally(new RuntimeException("IAT failed: " + iatResponse.getMessage()));
                    latch.countDown();
                    return;
                }

                if (iatResponse.getData() != null && iatResponse.getData().getResult() != null) {
                    IatResult result = iatResponse.getData().getResult();
                    Text textObject = result.getText();
                    if (textObject != null) {
                        handleResultText(textObject, resultSegments);
                    }

                    if (iatResponse.getData().getStatus() == 2) {
                        future.complete(getFinalResult(resultSegments));
                        latch.countDown();
                    }
                }
            }

            @Override
            public void onFail(WebSocket webSocket, Throwable t, Response response) {
                future.completeExceptionally(t);
                latch.countDown();
            }
        };

        try {
            Path tempDir = storageProperties.getAudioTempPath();
            Files.createDirectories(tempDir);

            String fileName = "audio_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId() + ".tmp";
            Path tempFilePath = tempDir.resolve(fileName);
            try (var inputStream = audioFile.getInputStream()) {
                Files.copy(inputStream, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
            }

            File tempFile = tempFilePath.toFile();
            iatClient.send(tempFile, listener);

            if (!latch.await(60, TimeUnit.SECONDS) && !future.isDone()) {
                future.completeExceptionally(new RuntimeException("IAT timeout"));
            }
            cleanupTempFile(tempFilePath);
        } catch (Exception ex) {
            future.completeExceptionally(ex);
        }

        return future;
    }

    /**
     * 【面试重点】实时流式转写入口 —— 整个 ASR 链路最核心的方法之一。
     *
     * 调用方（AudioTranscriptionWebSocketHandler）传入的是 Pipe 的 audioInputStream（读端），
     * 不是前端 ByteBuffer。这是生产者-消费者模型的关键：前端写 Pipe 写端，这边读 Pipe 读端。
     *
     * 完整流程：
     * ① 签名 → 拼 AST WebSocket URL（HmacSHA1 + TreeMap 排序参数）
     * ② onOpen → 异步启动 sendAudioStream（从 Pipe 读端消费音频推给讯飞）
     * ③ onMessage → 解析 AST JSON → 提取字段 → Assembler 增量去重 → 三级文本渲染 → callback 推 Handler
     * ④ finalPacket 或 onClosed → future.complete() → Handler 的 whenComplete 收尾
     *
     * 关键设计：
     * - assembler：TreeMap<Integer, SegmentState>，有序分段池，key 是 seg_id
     * - fallbackSn：AtomicInteger 自增兜底序号，防止 seg_id 缺失导致 TreeMap 没有 key
     * - revisionCounter：每次推送递增，供前端判断"是新数据还是重复推送"
     * - latestDisplay：StringBuilder 缓存上一次展示文本，避免推送未变化的重复包
     */
    public CompletableFuture<String> realTimeAudioToText(InputStream audioInputStream, AudioResultCallback callback) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (audioInputStream == null) {
            future.completeExceptionally(new ClientException("audioInputStream cannot be null"));
            return future;
        }

        String appId = normalize(xunfeiLatPropertiesConfig.getAppId());
        String apiKey = normalize(xunfeiLatPropertiesConfig.getApiKey());
        String apiSecret = normalize(xunfeiLatPropertiesConfig.getApiSecret());

        if (StrUtil.isBlank(appId) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(apiSecret)) {
            future.completeExceptionally(new RuntimeException(
                    "Large-model realtime ASR requires appId/apiKey/apiSecret"));
            return future;
        }

        String sessionId = UUID.randomUUID().toString();
        String wsUrl;
        try {
            wsUrl = buildAstUrl(appId, apiKey, apiSecret, sessionId);
        } catch (Exception ex) {
            future.completeExceptionally(new RuntimeException("Failed to build AST websocket URL", ex));
            return future;
        }

        AstTranscriptionAssembler assembler = new AstTranscriptionAssembler();
        AtomicInteger fallbackSn = new AtomicInteger(0);
        AtomicInteger rawPacketCounter = new AtomicInteger(0);
        AtomicInteger revisionCounter = new AtomicInteger(0);
        StringBuilder latestDisplay = new StringBuilder();
        Request request = new Request.Builder().url(wsUrl).build();
        WS_CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                CompletableFuture.runAsync(
                        () -> sendAudioStream(webSocket, audioInputStream, sessionId, future),
                        queryExecutor
                );
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    int packetNo = rawPacketCounter.incrementAndGet();
                    log.info("[ASR_RAW_PACKET] sessionId={}, packetNo={}, payload={}", sessionId, packetNo, text);
                    JSONObject root = JSONObject.parseObject(text);
                    String action = root.getString("action");
                    if ("error".equalsIgnoreCase(action)) {
                        String code = root.getString("code");
                        String desc = root.getString("desc");
                        future.completeExceptionally(new RuntimeException(
                                "AST business failure: code=" + code + ", desc=" + desc + ", raw=" + text));
                        return;
                    }

                    JSONObject st = extractAstSt(root);
                    String partialText = extractAstText(root);
                    int segmentId = resolveSegmentId(root, st, fallbackSn);
                    String pgs = st != null ? st.getString("pgs") : null;
                    Integer bg = st != null ? st.getInteger("bg") : null;
                    Integer ed = st != null ? st.getInteger("ed") : null;
                    int[] rg = extractRg(st);
                    boolean finalPacket = isAstFinal(root);
                    if (StrUtil.isNotBlank(partialText)) {
                        assembler.apply(segmentId, pgs, rg, bg, ed, partialText, finalPacket);
                        String merged = assembler.buildSnapshot();
                        if (!merged.equals(latestDisplay.toString())) {
                            String committedText = assembler.buildCommittedText(segmentId, finalPacket);
                            String liveText = assembler.buildLiveText(committedText, merged, partialText, finalPacket);
                            latestDisplay.setLength(0);
                            latestDisplay.append(merged);
                            if (callback != null) {
                                callback.onResult(new RealtimeTranscriptionUpdate(
                                        merged,
                                        committedText,
                                        liveText,
                                        merged,
                                        revisionCounter.incrementAndGet(),
                                        finalPacket ? "final" : "partial",
                                        segmentId,
                                        partialText,
                                        pgs,
                                        rg,
                                        bg,
                                        ed,
                                        finalPacket
                                ));
                            }
                        }
                    }

                    if (finalPacket) {
                        if (!future.isDone()) {
                            String finalText = assembler.buildSnapshot();
                            if (StrUtil.isBlank(finalText)) {
                                finalText = latestDisplay.toString();
                            }
                            future.complete(finalText);
                        }
                        webSocket.close(1000, "completed");
                    }
                } catch (Exception ex) {
                    if (!future.isDone()) {
                        future.completeExceptionally(new RuntimeException("Failed to parse AST response: " + text, ex));
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("AST websocket failure", t));
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!future.isDone()) {
                    String finalText = assembler.buildSnapshot();
                    if (StrUtil.isBlank(finalText)) {
                        finalText = latestDisplay.toString();
                    }
                    future.complete(finalText);
                }
            }
        });

        return future;
    }

    /**
     * 从 AST 响应中提取 st（sentence）对象。讯飞 AST 的 JSON 结构不规范：
     * st 可能在 root.st、root.data.st、root.data.cn.st 三个位置之一。
     * 三重回退保证兼容不同版本协议。
     */
    private JSONObject extractAstSt(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject data = root.getJSONObject("data");
        if (data != null) {
            JSONObject cn = data.getJSONObject("cn");
            if (cn != null && cn.getJSONObject("st") != null) {
                return cn.getJSONObject("st");
            }
            if (data.getJSONObject("st") != null) {
                return data.getJSONObject("st");
            }
        }

        JSONObject cn = root.getJSONObject("cn");
        if (cn != null && cn.getJSONObject("st") != null) {
            return cn.getJSONObject("st");
        }
        return root.getJSONObject("st");
    }

    /**
     * 【面试重点】seg_id 提取 + fallback 兜底。三层防御：
     * ① 从三个位置提取 seg_id（data.seg_id → st.seg_id → st.sn）
     * ② 提取成功 → 同步 fallbackSn = max(当前值, 真实 seg_id)，防止自增序号和真实序号冲突
     * ③ 提取失败 → fallbackSn.incrementAndGet() 自增生成一个，保证 TreeMap 的 key 永不为空
     */
    private int resolveSegmentId(JSONObject root, JSONObject st, AtomicInteger fallbackSn) {
        Integer segId = extractSegmentId(root, st);
        if (segId != null) {
            fallbackSn.set(Math.max(fallbackSn.get(), segId));
            return segId;
        }
        return fallbackSn.incrementAndGet();
    }

    private Integer extractSegmentId(JSONObject root, JSONObject st) {
        JSONObject data = root != null ? root.getJSONObject("data") : null;
        Integer segId = data != null ? data.getInteger("seg_id") : null;
        if (segId != null) {
            return segId;
        }

        segId = st != null ? st.getInteger("seg_id") : null;
        if (segId != null) {
            return segId;
        }

        return st != null ? st.getInteger("sn") : null;
    }

    private int[] extractRg(JSONObject st) {
        if (st == null) {
            return null;
        }
        JSONArray rg = st.getJSONArray("rg");
        if (rg == null || rg.size() < 2) {
            return null;
        }

        Integer start = rg.getInteger(0);
        Integer end = rg.getInteger(1);
        if (start == null || end == null) {
            return null;
        }
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        return new int[]{start, end};
    }

    /**
     * 【面试重点】基于 pgs 的分段合并策略。pgs 是讯飞 AST 的增量修正模式标识：
     * - rpl（replace）：按 rg [start, end] 范围删除旧分段，再插入新分段（修正早期识别）
     * - apd（append）：追加一个新分段，不清除旧分段
     * - 其他/为空：退化到 upsert
     */
    private void applyAstSegment(TreeMap<Integer, SegmentState> sentencePool,
                                 int sn,
                                 String pgs,
                                 int[] rg,
                                 String text,
                                 boolean finalized) {
        if (sentencePool == null || text == null) {
            return;
        }

        if ("rpl".equalsIgnoreCase(pgs)) {
            if (rg != null) {
                for (int i = rg[0]; i <= rg[1]; i++) {
                    sentencePool.remove(i);
                }
            }
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        if ("apd".equalsIgnoreCase(pgs)) {
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        upsertAstSegment(sentencePool, sn, text, finalized);
    }

    /**
     * 【面试重点】无 pgs 时的 bg/ed 字节位置去重 —— 比 pgs 模式更复杂。
     * 四个分支按优先级执行：
     * ① bg/ed 完全匹配 + 新文本仅标点 → 标点追加到旧文本末尾，不做替换
     * ② 找到 bg/ed 重叠且文本相似的旧分段 → 复用（更新文本和 range）
     * ③ 新分段完全覆盖某些旧分段且相似 → 删除被覆盖的旧分段，插入新分段
     * ④ 没找到任何可复用的 → 直接 upsert
     *
     * 核心判断是 isLikelySameSegmentEvolution（公共前缀 ≥ 80%）
     * 和 findReusableRangeState（重叠率 ≥ 60%）
     */
    private void applyAstSegmentWithoutPgs(TreeMap<Integer, SegmentState> sentencePool,
                                           int sn,
                                           Integer bg,
                                           Integer ed,
                                           String text,
                                           boolean finalized) {
        if (sentencePool == null || text == null) {
            return;
        }

        if (bg == null || ed == null) {
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        SegmentState sameRange = findExactRangeState(sentencePool, bg, ed);
        if (sameRange != null && isPunctuationOnly(text) && StrUtil.isNotBlank(sameRange.text)) {
            sameRange.text = appendTrailingPunctuation(sameRange.text, text);
            sameRange.finalized = sameRange.finalized || finalized;
            sameRange.updatedAt = System.currentTimeMillis();
            return;
        }

        SegmentState reusable = findReusableRangeState(sentencePool, bg, ed, text);
        if (reusable != null) {
            updateSegmentState(reusable, text, finalized, bg, ed);
            removeCoveredSiblingRangeStates(sentencePool, reusable.segId, bg, ed, text);
            return;
        }

        removeCoveredSiblingRangeStates(sentencePool, null, bg, ed, text);
        upsertAstSegment(sentencePool, sn, text, finalized);
        SegmentState state = sentencePool.get(sn);
        if (state != null) {
            state.bg = bg;
            state.ed = ed;
        }
    }

    private SegmentState findExactRangeState(TreeMap<Integer, SegmentState> sentencePool, Integer bg, Integer ed) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty()) {
            return null;
        }
        for (SegmentState state : sentencePool.values()) {
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (state.bg.equals(bg) && state.ed.equals(ed)) {
                return state;
            }
        }
        return null;
    }

    private SegmentState findReusableRangeState(TreeMap<Integer, SegmentState> sentencePool,
                                                Integer bg,
                                                Integer ed,
                                                String text) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty() || StrUtil.isBlank(text)) {
            return null;
        }

        SegmentState best = null;
        double bestScore = -1D;
        for (SegmentState state : sentencePool.values()) {
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (!isRangeOverlapping(bg, ed, state.bg, state.ed)) {
                continue;
            }
            if (state.bg.equals(bg) && state.ed.equals(ed)) {
                return state;
            }

            double overlapRatio = calculateOverlapRatio(bg, ed, state.bg, state.ed);
            if (overlapRatio < 0.6D || !isLikelySameSegmentEvolution(state.text, text)) {
                continue;
            }

            double score = overlapRatio;
            if (containsComparableText(text, state.text)) {
                score += 1D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = state;
            }
        }
        return best;
    }

    private void removeCoveredSiblingRangeStates(TreeMap<Integer, SegmentState> sentencePool,
                                                 Integer retainedSegId,
                                                 Integer bg,
                                                 Integer ed,
                                                 String text) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, SegmentState>> iterator = sentencePool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, SegmentState> entry = iterator.next();
            SegmentState state = entry.getValue();
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (retainedSegId != null && retainedSegId.equals(entry.getKey())) {
                continue;
            }
            if (isRangeFullyCoveredBy(bg, ed, state.bg, state.ed)
                    && isLikelySameSegmentEvolution(state.text, text)) {
                iterator.remove();
            }
        }
    }

    private boolean isRangeOverlapping(int bg1, int ed1, int bg2, int ed2) {
        return bg1 <= ed2 && bg2 <= ed1;
    }

    private boolean isRangeFullyCoveredBy(int outerBg, int outerEd, int innerBg, int innerEd) {
        return outerBg <= innerBg && outerEd >= innerEd;
    }

    private double calculateOverlapRatio(int bg1, int ed1, int bg2, int ed2) {
        int overlapStart = Math.max(bg1, bg2);
        int overlapEnd = Math.min(ed1, ed2);
        int overlap = overlapEnd - overlapStart;
        if (overlap <= 0) {
            return 0D;
        }
        int span1 = Math.max(1, ed1 - bg1);
        int span2 = Math.max(1, ed2 - bg2);
        return (double) overlap / Math.min(span1, span2);
    }

    private boolean isPunctuationOnly(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String trimmed = rtrim(text);
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    private String appendTrailingPunctuation(String baseText, String punctuation) {
        String base = rtrim(baseText);
        String suffix = rtrim(punctuation);
        if (suffix.isEmpty()) {
            return base;
        }
        if (base.endsWith(suffix)) {
            return base;
        }
        return base + suffix;
    }

    private String rtrim(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private boolean isLikelySameSegmentEvolution(String existingText, String incomingText) {
        String existingComparable = toComparableText(existingText);
        String incomingComparable = toComparableText(incomingText);
        if (existingComparable.isEmpty() || incomingComparable.isEmpty()) {
            return false;
        }
        if (existingComparable.equals(incomingComparable)) {
            return true;
        }
        if (incomingComparable.contains(existingComparable) || existingComparable.contains(incomingComparable)) {
            return true;
        }
        int commonPrefix = commonPrefixLength(existingComparable, incomingComparable);
        int minLength = Math.min(existingComparable.length(), incomingComparable.length());
        return minLength > 0 && ((double) commonPrefix / minLength) >= 0.8D;
    }

    private boolean containsComparableText(String source, String candidate) {
        String sourceComparable = toComparableText(source);
        String candidateComparable = toComparableText(candidate);
        if (sourceComparable.isEmpty() || candidateComparable.isEmpty()) {
            return false;
        }
        return sourceComparable.contains(candidateComparable);
    }

    private String toComparableText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private void updateSegmentState(SegmentState state,
                                    String text,
                                    boolean finalized,
                                    Integer bg,
                                    Integer ed) {
        if (state == null) {
            return;
        }
        state.text = text;
        state.finalized = state.finalized || finalized;
        state.bg = bg;
        state.ed = ed;
        state.updatedAt = System.currentTimeMillis();
    }

    private void upsertAstSegment(TreeMap<Integer, SegmentState> sentencePool,
                                  int sn,
                                  String text,
                                  boolean finalized) {
        SegmentState state = sentencePool.get(sn);
        if (state == null) {
            state = new SegmentState(sn);
        }
        state.text = text;
        state.finalized = state.finalized || finalized;
        state.updatedAt = System.currentTimeMillis();
        sentencePool.put(sn, state);
    }

    private String buildFinalResult(Map<Integer, SegmentState> sentencePool) {
        if (sentencePool == null || sentencePool.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (SegmentState part : sentencePool.values()) {
            if (part != null && part.text != null) {
                result.append(part.text);
            }
        }
        return result.toString();
    }

    /**
     * 【面试重点】消费者端：从 Pipe 读端持续读取音频，按讯飞 AST 协议推流。
     *
     * - 每次读 1280 字节（AST 协议块大小）
     * - 每发一块 sleep(40ms)，模拟实时推流节奏（不是越快越好，AST 期望近似实时的速率）
     * - 读完 EOF 后发 {"end":true} 告知讯飞音频结束
     * - 这里读的是 Pipe 的 InputStream，不是前端 WebSocket Session
     * - 如果 Pipe 写端被 stopTranscriptionSession 关闭，这里会收到 IOException → future 完成 → onFailure 收尾
     */
    private void sendAudioStream(WebSocket webSocket,
                                 InputStream audioInputStream,
                                 String sessionId,
                                 CompletableFuture<String> future) {
        byte[] buffer = new byte[CHUNK_SIZE_BYTES];
        try (InputStream in = audioInputStream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                byte[] chunk = read == buffer.length ? buffer : copyOf(buffer, read);
                boolean ok = webSocket.send(okio.ByteString.of(chunk, 0, chunk.length));
                if (!ok) {
                    throw new RuntimeException("AST websocket send returned false");
                }
                Thread.sleep(40);
            }
            webSocket.send("{\"end\":true,\"sessionId\":\"" + sessionId + "\"}");
        } catch (Exception ex) {
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException("Failed to send AST audio stream", ex));
            }
            webSocket.close(1011, "send failed");
        }
    }

    private String buildAstUrl(String appId, String apiKey, String apiSecret, String sessionId) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appId", appId);
        params.put("accessKeyId", apiKey);
        params.put("audio_encode", "pcm_s16le");
        params.put("lang", "autodialect");
        params.put("samplerate", "16000");
        params.put("sessionId", sessionId);
        params.put("utc", OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")));
        params.put("uuid", UUID.randomUUID().toString().replace("-", ""));

        String signTarget = buildCanonicalQuery(params);
        String signature = hmacSha1Base64(signTarget, apiSecret);
        params.put("signature", signature);

        return AST_WS_BASE_URL + "?" + buildCanonicalQuery(params);
    }

    private String buildCanonicalQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(urlEncode(e.getKey())).append("=").append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            throw new RuntimeException("URL encode failed", ex);
        }
    }

    private String hmacSha1Base64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] signBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signBytes);
    }

    private byte[] copyOf(byte[] src, int len) {
        byte[] dst = new byte[len];
        System.arraycopy(src, 0, dst, 0, len);
        return dst;
    }

    private String extractAstText(JSONObject root) {
        JSONObject st = extractAstSt(root);
        if (st == null) {
            return "";
        }
        JSONArray rtArr = st.getJSONArray("rt");
        if (rtArr == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        JSONObject rt = rtArr.getJSONObject(0);
        if (rt == null) {
            return "";
        }

        JSONArray wsArr = rt.getJSONArray("ws");
        if (wsArr == null) {
            return "";
        }

        for (int j = 0; j < wsArr.size(); j++) {
            JSONObject ws = wsArr.getJSONObject(j);
            JSONArray cwArr = ws.getJSONArray("cw");
            if (cwArr == null || cwArr.isEmpty()) {
                continue;
            }

            JSONObject cw = cwArr.getJSONObject(0);
            if (cw == null) {
                continue;
            }
            String w = cw.getString("w");
            if (w != null) {
                sb.append(w);
            }
        }
        return sb.toString();
    }

    private boolean isAstFinal(JSONObject root) {
        JSONObject data = root.getJSONObject("data");
        if (data != null && Boolean.TRUE.equals(data.getBoolean("ls"))) {
            return true;
        }
        JSONObject st = extractAstSt(root);
        return st != null && Boolean.TRUE.equals(st.getBoolean("ls"));
    }

    /**
     * 【面试重点】AST 增量装配器 —— ASR 算法的核心。
     *
     * 维护一个 TreeMap<Integer, SegmentState> 有序分段池，key 是 seg_id，按自然序排列。
     * 讯飞 AST 返回的每个增量包经过这里做合并，而不是直接原始追加。
     *
     * apply() 的分发逻辑：
     * - 有 pgs → applyAstSegment（rpl 删除范围 + 插入 / apd 追加）
     * - 无 pgs → applyAstSegmentWithoutPgs（bg/ed 范围重叠 + 文本相似度匹配）
     *
     * 三类文本的计算（三级渲染的核心实现）：
     * - buildSnapshot()：遍历 TreeMap values 全量拼接 = displayText
     * - buildCommittedText(activeSegId, finalPacket)：只取 seg_id < activeSegId 或 finalized=true 的分段
     * - buildLiveText(committed, display, segment, finalPacket)：displayText - committedText 前缀
     */
    private final class AstTranscriptionAssembler {
        // TreeMap 按 seg_id 自然序排列，保证 buildSnapshot() 输出文本顺序正确
        private final TreeMap<Integer, SegmentState> segments = new TreeMap<>();

        /**
         * 入口：根据是否有 pgs 分发到不同的合并策略。
         */
        private void apply(int segmentId,
                           String pgs,
                           int[] rg,
                           Integer bg,
                           Integer ed,
                           String text,
                           boolean finalized) {
            if (StrUtil.isBlank(pgs)) {
                applyAstSegmentWithoutPgs(segments, segmentId, bg, ed, text, finalized);
                return;
            }
            applyAstSegment(segments, segmentId, pgs, rg, text, finalized);
        }

        private String buildSnapshot() {
            return buildFinalResult(segments);
        }

        private String buildCommittedText(int activeSegmentId, boolean finalPacket) {
            if (finalPacket) {
                return buildSnapshot();
            }
            if (segments.isEmpty()) {
                return "";
            }

            StringBuilder committed = new StringBuilder();
            for (SegmentState segment : segments.values()) {
                if (segment == null || segment.text == null) {
                    continue;
                }
                if (segment.segId < activeSegmentId || segment.finalized) {
                    committed.append(segment.text);
                }
            }
            return committed.toString();
        }

        private String buildLiveText(String committedText,
                                     String displayText,
                                     String segmentText,
                                     boolean finalPacket) {
            if (finalPacket) {
                return "";
            }
            if (StrUtil.isBlank(displayText)) {
                return "";
            }
            String committed = committedText != null ? committedText : "";
            if (!committed.isEmpty() && displayText.startsWith(committed)) {
                return displayText.substring(committed.length());
            }
            return segmentText != null ? segmentText : displayText;
        }
    }

    /**
     * 分段状态 —— TreeMap 的 value 对象。
     * segId: AST 分段序号（不可变）
     * text: 当前识别文本（可被 rpl 替换或 apd 更新）
     * finalized: 该分段是否已稳定（讯飞标记 ls=true 或已被后续修正"覆盖确认"）
     * bg/ed: 音频帧字节偏移，无 pgs 时用于范围去重比对
     */
    private static final class SegmentState {
        private final int segId;
        private String text;
        private boolean finalized;
        private Integer bg;
        private Integer ed;
        private long updatedAt;

        private SegmentState(int segId) {
            this.segId = segId;
        }
    }

    private static void handleResultText(Text textObject, List<Text> resultSegments) {
        if (StringUtils.equals(textObject.getPgs(), "rpl")
                && textObject.getRg() != null
                && textObject.getRg().length == 2) {
            int start = textObject.getRg()[0] - 1;
            int end = textObject.getRg()[1] - 1;
            for (int i = start; i <= end && i < resultSegments.size(); i++) {
                resultSegments.get(i).setDeleted(true);
            }
        }
        resultSegments.add(textObject);
    }

    private static String getFinalResult(List<Text> resultSegments) {
        StringBuilder finalResult = new StringBuilder();
        for (Text text : resultSegments) {
            if (text != null && !text.isDeleted()) {
                finalResult.append(text.getText());
            }
        }
        return finalResult.toString();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private String mask(String value) {
        if (StrUtil.isBlank(value)) {
            return "null";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile == null || !Files.exists(tempFile)) {
            return;
        }
        try {
            Files.delete(tempFile);
        } catch (Exception ex) {
            tempFile.toFile().deleteOnExit();
            log.warn("Failed to delete temp file: {}", tempFile);
        }
    }

    public interface AudioResultCallback {
        void onResult(RealtimeTranscriptionUpdate result);
    }

    /**
     * 【面试重点】转写增量更新 —— 后端三层适配的产出物。
     *
     * 三级文本（解决结果抖动）：
     * - fullText: 全量快照
     * - displayText: 前端应展示的完整文本（committedText + liveText）
     * - committedText: 已稳定文本，前端用深色渲染
     * - liveText: 仍在变的尾部，前端用浅色/斜体渲染
     *
     * 版本控制：
     * - revision: 递增版本号，前端靠它判断是新数据还是重复推送
     * - resultStatus: "partial"（增量）/ "final"（终态）
     *
     * 讯飞分段元信息（透传给前端）：
     * - segmentId / segmentText / pgs / rg / bg / ed / finalPacket
     */
    public record RealtimeTranscriptionUpdate(String fullText,
                                              String committedText,
                                              String liveText,
                                              String displayText,
                                              Integer revision,
                                              String resultStatus,
                                              Integer segmentId,
                                              String segmentText,
                                              String pgs,
                                              int[] rg,
                                              Integer bg,
                                              Integer ed,
                                              boolean finalPacket) {
    }
}
