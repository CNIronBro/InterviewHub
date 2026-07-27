package com.ironbro.interviewhub.interview.application.guard.singleflight.service;

import cn.hutool.core.util.StrUtil;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardException;
import com.ironbro.interviewhub.interview.application.guard.singleflight.cache.FlightReplayLocalCache;
import com.ironbro.interviewhub.interview.application.guard.singleflight.cache.FlightResultSerializer;
import com.ironbro.interviewhub.interview.application.guard.singleflight.coordinator.FlightCoordinatorRepository;
import com.ironbro.interviewhub.interview.application.guard.singleflight.coordinator.FlightHeartbeatManager;
import com.ironbro.interviewhub.interview.application.guard.singleflight.coordinator.FlightNotificationService;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightAcquireResult;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightErrorType;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightMetaSnapshot;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightMode;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightOwnerContext;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightStatus;
import com.ironbro.interviewhub.interview.application.guard.singleflight.model.FlightStoredResult;
import com.ironbro.interviewhub.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 分布式 AI single-flight 核心服务，负责在集群内协调 owner 与 follower，
 * 完成请求抢占、结果复用、失败接管以及本地降级回退。
 *
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedInterviewAiSingleFlightService {

    // 配置：开关、模式(LOCAL/HYBRID/DISTRIBUTED)、stage 策略
    private final InterviewAiSingleFlightConfiguration configuration;
    // 本地 Single-flight（ConcurrentHashMap + CompletableFuture），分布式不可用时的降级方案
    private final InterviewAiSingleFlightService localSingleFlightService;
    // Redis 仓储：Lua 脚本执行 acquice/join、markRunning、heartbeat、storeResult、finishSuccess/Failure
    private final FlightCoordinatorRepository flightCoordinatorRepository;
    // Redis Stream 通知：owner 完成后发布事件，follower 阻塞读取
    private final FlightNotificationService flightNotificationService;
    // 心跳管理器：owner 定时续期，防止被判定为宕机
    private final FlightHeartbeatManager flightHeartbeatManager;
    // 结果序列化器：压缩+编码 AI 响应，存入 Redis result key
    private final FlightResultSerializer flightResultSerializer;
    // L1 本地回放缓存：最近成功的结果缓存在 JVM 内存，避免重复查 Redis
    private final FlightReplayLocalCache flightReplayLocalCache;

    /**
     * 统一入口：根据配置决定走本地还是分布式 Single-flight。
     * - LOCAL 模式：直接走本地 ConcurrentHashMap + CompletableFuture
     * - DISTRIBUTED 模式：走 Redis Lua 协调，失败抛异常
     * - HYBRID 模式（默认）：先走分布式，分布式挂了降级到本地（兜底保障）
     * supplier 是包装了"AI 调用"的延迟执行逻辑，只有 owner 才真正调 supplier.get()
     */
    public String execute(String stage, String requestKey, Supplier<String> supplier) {
        flightReplayLocalCache.refreshMaxSize(configuration.getL1CacheMaxSize());
        FlightMode mode = FlightMode.from(configuration.normalizedMode());
        if (!Boolean.TRUE.equals(configuration.getEnable()) || mode == FlightMode.LOCAL || !Boolean.TRUE.equals(configuration.getDistributedEnabled())) {
            return localSingleFlightService.execute(requestKey, supplier);
        }
        try {
            return executeDistributed(stage, requestKey, supplier);
        } catch (RuntimeException ex) {
            if (mode == FlightMode.HYBRID) {
                log.warn("Distributed single-flight fallback to local mode, stage={}, key={}, reason={}", stage, requestKey, ex.getMessage());
                return localSingleFlightService.execute(requestKey, supplier);
            }
            throw ex;
        }
    }

    /**
     * 分布式协调核心：通过 Redis Lua 判断角色，五路分支。
     *
     * ① 先查 L1 本地缓存：最近成功的结果直接返回，零网络开销
     * ② 循环最多 3 次（处理角色切换和竞争）：
     *      acquireOrJoin（Redis Lua）→ 返回当前节点的动作
     *      - OWNER_NEW：新建 flight，当前节点是 owner → 执行 AI 调用
     *      - OWNER_TAKEOVER：前任 owner 心跳超时，当前节点接管 → 执行 AI 调用
     *      - REPLAY_SUCCESS：已有成功结果 → 读 Redis result key 回放
     *      - REPLAY_FAILURE：已有失败结果且不可重试 → 抛异常
     *      - FOLLOWER_WAIT：已有 owner 在跑 → 阻塞等待通知
     */
    private String executeDistributed(String stage, String requestKey, Supplier<String> supplier) {
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeRequestKey = StrUtil.blankToDefault(requestKey, safeStage + "|no-key");
        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy = configuration.resolveStagePolicy(safeStage);
        // ① L1 本地回放缓存：同一 JVM 内的快速路径
        String localReplay = flightReplayLocalCache.get(safeStage, safeRequestKey);
        if (localReplay != null) {
            return localReplay;
        }

        long deadline = System.currentTimeMillis() + resolveFollowerMaxWaitMillis();
        int attempts = 0;
        while (attempts < 3) { // 最多 3 次尝试，处理角色切换、竞争和状态变更
            attempts++;
            // ② Redis Lua 原子判断：当前节点是 owner、follower 还是直接回放
            FlightAcquireResult acquireResult = flightCoordinatorRepository.acquireOrJoin(
                    safeStage,
                    safeRequestKey,
                    nodeId(),
                    extractSessionId(safeRequestKey),
                    policy
            );
            if (acquireResult == null || acquireResult.getAction() == null) {
                return localSingleFlightService.execute(safeRequestKey, supplier); // 降级到本地
            }
            switch (acquireResult.getAction()) {
                case OWNER_NEW, OWNER_TAKEOVER -> {
                    // ③ owner 路径：执行 AI 调用
                    return ownerExecute(safeStage, safeRequestKey, acquireResult.getOwnerToken(), supplier, policy);
                }
                case REPLAY_SUCCESS -> {
                    // ④ 已有成功结果：从 Redis 读 result 回放
                    String replay = tryReadSuccessReplay(safeStage, safeRequestKey, policy);
                    if (replay != null) {
                        return replay;
                    }
                }
                case REPLAY_FAILURE -> throw replayFailure(acquireResult); // 不可重试的失败
                case FOLLOWER_WAIT -> {
                    // ⑤ follower 路径：阻塞等待 owner 完成通知
                    String followerReplay = followerWait(safeStage, safeRequestKey, policy, deadline);
                    if (followerReplay != null) {
                        return followerReplay;
                    }
                }
                default -> {
                    return localSingleFlightService.execute(safeRequestKey, supplier);
                }
            }
        }
        throw new CompletionException(new RejectedExecutionException("distributed single-flight max attempts exceeded"));
    }

    /**
     * owner 执行路径：标记运行 → 启动心跳 → 执行 AI → 存储结果 → 通知 follower。
     *
     * ① markRunning：将 meta 状态从 PENDING 推进到 RUNNING，防止被接管
     * ② 启动心跳：定时续期 meta 的 heartbeatAt/expireAt，防止被 follower 判定为宕机
     * ③ supplier.get()：真正执行 AI 调用（这里才进 Guard → XingChenAIClient）
     * ④ storeResult：将 AI 响应序列化存入 Redis result key
     * ⑤ finishSuccess：meta 状态推进到 SUCCEEDED
     * ⑥ publish 通知：发 Stream 消息给所有阻塞等待的 follower
     * ⑦ 写入 L1 本地缓存：同 JVM 后续请求直接命中
     * 失败路径：分类异常 → finishFailure → publish 失败通知
     */
    private String ownerExecute(String stage, String requestKey, Long ownerToken,
                                Supplier<String> supplier,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        long runningTtlMillis = positive(policy.getRunningTtlMillis(), 15000L);
        // ① 标记 RUNNING，推进状态机 PENDING → RUNNING
        boolean markedRunning = flightCoordinatorRepository.markRunning(requestKey, nodeId(), ownerToken, runningTtlMillis);
        if (!markedRunning) {
            // 标记失败说明状态已被改变，降级为 follower 等待
            return followerWait(stage, requestKey, policy, System.currentTimeMillis() + resolveFollowerMaxWaitMillis());
        }

        FlightOwnerContext ownerContext = FlightOwnerContext.builder()
                .stage(stage)
                .requestKey(requestKey)
                .ownerId(nodeId())
                .ownerToken(ownerToken)
                .policy(policy)
                .build();
        // ② 启动心跳：定期刷新 heartbeatAt 和 expireAt
        String heartbeatTaskKey = flightHeartbeatManager.start(
                ownerContext,
                () -> flightCoordinatorRepository.heartbeat(requestKey, nodeId(), ownerToken, runningTtlMillis)
        );
        try {
            // ③ 真正执行 AI 调用（supplier 内部是 Guard → XingChenAIClient）
            String result = supplier.get();
            // ④ 序列化并存储结果到 Redis result key
            FlightStoredResult storedResult = flightResultSerializer.serialize(result, ownerToken, policy);
            long resultTtlMillis = positive(policy.getResultTtlMillis(), 600000L);
            if (!flightCoordinatorRepository.storeResult(requestKey, nodeId(), ownerToken, storedResult, resultTtlMillis)) {
                throw new IllegalStateException("failed to store distributed flight result");
            }
            // ⑤ meta 状态推进到 SUCCEEDED
            if (!flightCoordinatorRepository.finishSuccess(requestKey, nodeId(), ownerToken, resultTtlMillis)) {
                String replay = tryReadSuccessReplay(stage, requestKey, policy);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException("failed to finish distributed flight success state");
            }
            // ⑥ 通知所有 follower：Redis Stream 发布成功事件
            flightNotificationService.publish(requestKey, "owner_succeeded", FlightStatus.SUCCEEDED, ownerToken, null, false);
            // ⑦ 写入 L1 本地缓存：同 JVM 后续请求零网络开销
            flightReplayLocalCache.put(stage, requestKey, result, policy);
            return result;
        } catch (Throwable ex) {
            // 失败路径：分类异常 → 写入失败状态 → 通知 follower
            FlightFailure failure = classifyFailure(ex);
            flightCoordinatorRepository.finishFailure(
                    requestKey,
                    nodeId(),
                    ownerToken,
                    failure.errorType,
                    failure.errorCode,
                    failure.retryable,
                    positive(policy.getFailedResultTtlMillis(), 60000L)
            );
            flightNotificationService.publish(requestKey, "owner_failed", FlightStatus.FAILED, ownerToken, failure.errorType, failure.retryable);
            throw rethrow(ex);
        } finally {
            // 无论成功失败，停止心跳
            flightHeartbeatManager.stop(heartbeatTaskKey);
        }
    }

    /**
     * follower 等待逻辑：Stream 阻塞读取 + 轮询兜底，双通道保证不丢通知。
     *
     * 流程：循环直到 deadline 超时
     *   ① 先查 L1 缓存 / Redis result（owner 可能已经完成了）
     *   ② 查 meta：如果 owner 失败了且不可重试 → 快速失败
     *   ③ Redis Stream 阻塞读取（XREAD BLOCK），等待 owner 发布完成事件
     *   ④ 轮询兜底：每 pollIntervalMillis 主动查一次 result（防止 Stream 消息丢失）
     */
    private String followerWait(String stage, String requestKey,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy,
                                long deadlineMillis) {
        long streamBlockTimeoutMillis = positive(configuration.getStreamBlockTimeoutMillis(), 3000L);
        long pollIntervalMillis = positive(configuration.getPollFallbackIntervalMillis(), 2000L);
        long nextPollAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            // ① 先查结果缓存（owner 可能已经完成）
            String replay = tryReadSuccessReplay(stage, requestKey, policy);
            if (replay != null) {
                return replay;
            }
            // ② 查 meta：owner 失败且不可重试 → 快速失败，不浪费时间等
            FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
            if (metaSnapshot != null && metaSnapshot.getStatus() == FlightStatus.FAILED && !Boolean.TRUE.equals(metaSnapshot.getRetryable())) {
                throw new IllegalStateException("distributed single-flight previous failure: "
                        + (metaSnapshot.getErrorCode() == null ? "FAILED" : metaSnapshot.getErrorCode()));
            }
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return null; // 超时，返回 null 让外层重试或降级
            }
            // ③ Redis Stream 阻塞等待 owner 的完成通知
            flightNotificationService.waitForTerminalEvent(requestKey, Math.min(streamBlockTimeoutMillis, remainingMillis));
            // ④ 轮询兜底：每 pollIntervalMillis 主动查一次（防止 Stream 消息丢失）
            if (System.currentTimeMillis() >= nextPollAt) {
                String polledReplay = tryReadSuccessReplay(stage, requestKey, policy);
                if (polledReplay != null) {
                    return polledReplay;
                }
                nextPollAt = System.currentTimeMillis() + pollIntervalMillis;
            }
        }
        return null;
    }

    /**
     * 三级回放读取：L1 本地缓存 → L2 Redis meta → L2 Redis result → 回写 L1。
     * 链路上的任何一个节点（follower、新请求）都可以通过这个方法拿到已完成的结果。
     */
    private String tryReadSuccessReplay(String stage, String requestKey,
                                        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        // L1：本地 JVM 缓存（最近成功的结果）
        String localReplay = flightReplayLocalCache.get(stage, requestKey);
        if (localReplay != null) {
            return localReplay;
        }
        // L2：Redis meta key，确认状态是 SUCCEEDED
        FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
        if (metaSnapshot == null || metaSnapshot.getStatus() != FlightStatus.SUCCEEDED) {
            return null;
        }
        // L2：Redis result key，读取反序列化结果
        FlightStoredResult storedResult = flightCoordinatorRepository.getStoredResult(requestKey);
        if (storedResult == null) {
            return null;
        }
        String replay = flightResultSerializer.deserialize(storedResult);
        // 回写 L1 缓存：下次同 JVM 请求零网络开销
        flightReplayLocalCache.put(stage, requestKey, replay, policy);
        return replay;
    }

    private RuntimeException replayFailure(FlightAcquireResult acquireResult) {
        boolean retryable = Boolean.TRUE.equals(acquireResult.getRetryable());
        String message = "distributed single-flight replay failure";
        if (StrUtil.isNotBlank(acquireResult.getErrorCode())) {
            message = message + ": " + acquireResult.getErrorCode();
        }
        if (retryable) {
            return new CompletionException(new RejectedExecutionException(message));
        }
        return new IllegalStateException(message);
    }

    private RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(throwable);
    }

    /**
     * 异常分类：决定失败是否可以重试接管。
     * - TIMEOUT/OVERLOAD/PROVIDER → retryable=true，follower 可以接管重试
     * - VALIDATION/UNEXPECTED → retryable=false，直接失败不接管
     * 这个设计保证临时性故障（超时/过载）可以被接管，而业务错误（参数非法）不会反复重试
     */
    private FlightFailure classifyFailure(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof InterviewAiGuardException guardException) {
            return switch (guardException.getErrorCode()) {
                case AI_TIMEOUT -> new FlightFailure(FlightErrorType.TIMEOUT, guardException.getErrorCode().name(), true);
                case AI_OVERLOADED -> new FlightFailure(FlightErrorType.OVERLOAD, guardException.getErrorCode().name(), true);
                case AI_UNAVAILABLE -> new FlightFailure(FlightErrorType.PROVIDER, guardException.getErrorCode().name(), true);
            };
        }
        if (cause instanceof TimeoutException) {
            return new FlightFailure(FlightErrorType.TIMEOUT, "TIMEOUT", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return new FlightFailure(FlightErrorType.OVERLOAD, "OVERLOADED", true);
        }
        if (cause instanceof IllegalArgumentException) {
            return new FlightFailure(FlightErrorType.VALIDATION, "VALIDATION", false);
        }
        return new FlightFailure(FlightErrorType.UNEXPECTED, "UNEXPECTED", false);
    }

    private long resolveFollowerMaxWaitMillis() {
        return positive(configuration.getFollowerMaxWaitMillis(), 20000L);
    }

    private long positive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private String extractSessionId(String requestKey) {
        if (StrUtil.isBlank(requestKey)) {
            return null;
        }
        String[] parts = requestKey.split("\\|");
        return parts.length > 1 ? parts[1] : null;
    }

    private String nodeId() {
        return Holder.NODE_ID;
    }

    /**
     * 懒加载当前节点标识的内部工具类，用于生成 owner 节点身份。
     *
     */
    private static final class Holder {
        private static final String NODE_ID = resolveNodeId();

        private static String resolveNodeId() {
            try {
                return InetAddress.getLocalHost().getHostName() + "@" + ManagementFactory.getRuntimeMXBean().getName();
            } catch (UnknownHostException ex) {
                return ManagementFactory.getRuntimeMXBean().getName();
            }
        }
    }

    /**
     * 分布式协调过程中对异常进行归类后的内部失败对象，
     * 用于统一写入失败状态并决定是否允许重试接管。
     *
     */
    private record FlightFailure(FlightErrorType errorType, String errorCode, boolean retryable) {
    }
}
