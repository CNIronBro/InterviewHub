package com.ironbro.interviewhub.interview.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.interview.api.io.req.InterviewConversationPageReqDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewConversationRespDTO;
import com.ironbro.interviewhub.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.ironbro.interviewhub.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.ironbro.interviewhub.interview.application.InterviewSessionOwnershipService;
import com.ironbro.interviewhub.interview.dao.entity.InterviewSession;
import com.ironbro.interviewhub.interview.dao.repository.InterviewSessionRepository;
import com.ironbro.interviewhub.interview.service.InterviewSessionService;
import com.ironbro.interviewhub.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewSessionServiceImpl implements InterviewSessionService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewSessionOwnershipService ownershipService;
    private final BusinessAgentResolver businessAgentResolver;
    private final ObjectProvider<InterviewSessionRuntimeSnapshotService> runtimeSnapshotServiceProvider;

    @Override
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        abandonActiveSessions(userId);

        InterviewSession session = new InterviewSession();
        session.setSessionId(IdUtil.getSnowflakeNextIdStr());
        session.setUserId(userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        session.setConversationTitle("Interview Session");
        session.setInterviewerAgentId(
                businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING).getId());
        session.setDelFlag(0);
        interviewSessionRepository.save(session);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = runtimeSnapshotServiceProvider.getIfAvailable();
        if (runtimeSnapshotService != null) {
            runtimeSnapshotService.initializeDraftSnapshot(session);
        }
        return new InterviewSessionCreateRespDTO(session.getSessionId(), session.getStatus());
    }

    @Override
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        Pageable pageable = PageRequest.of(requestParam.getCurrent() - 1, requestParam.getSize());
        org.springframework.data.domain.Page<InterviewSession> sessionPage = queryPage(userId, requestParam, pageable);
        Page<InterviewConversationRespDTO> result = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        result.setTotal(sessionPage.getTotalElements());
        result.setRecords(sessionPage.getContent().stream().map(this::toRespDTO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public InterviewSession getBySessionId(String sessionId) {
        return interviewSessionRepository.findBySessionIdAndDelFlag(sessionId, 0).orElse(null);
    }

    @Override
    public InterviewSession requireOwnedSession(String sessionId, Long userId) {
        return ownershipService.requireOwnedSession(sessionId, userId);
    }

    // ==================== 会话状态推进方法 ====================
    // 状态机：DRAFT → UPLOADING → READY → IN_PROGRESS → FINISHED

    /** 标记"简历上传中"：抢占状态，防止并发上传重复触发 AI 出题 */
    @Override
    public void markResumeUploading(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId); // 校验归属
        session.setStatus(InterviewSessionStatus.RESUME_UPLOADING.name());
        interviewSessionRepository.save(session);
    }

    /** 标记"就绪"：简历解析成功，同步简历URL和面试方向到会话 */
    @Override
    public void markReady(String sessionId, Long userId, String resumeFileUrl, String interviewType) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.READY.name());
        session.setResumeFileUrl(resumeFileUrl);   // 简历文件的 OSS 地址
        session.setInterviewType(interviewType);    // 面试方向（如"Java后端"）
        interviewSessionRepository.save(session);
    }

    /** 回落到"草稿"：简历解析失败，允许用户重新上传 */
    @Override
    public void markDraft(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        interviewSessionRepository.save(session);
    }

    /** 首次答题时从 READY 提升到 IN_PROGRESS，并记录面试开始时间（只记一次） */
    @Override
    public void markInProgressIfReady(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        if (!InterviewSessionStatus.READY.name().equals(session.getStatus())) {
            return; // 只有 READY 才推进，已经是 IN_PROGRESS 的不重复处理
        }
        session.setStatus(InterviewSessionStatus.IN_PROGRESS.name());
        if (session.getStartTime() == null) {
            session.setStartTime(new Date()); // 首次答题时间作为面试开始时间
        }
        interviewSessionRepository.save(session);
    }

    @Override
    public void finishSession(String sessionId, Long userId) {
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        if (session.getStartTime() == null) {
            session.setStartTime(session.getCreateTime() == null ? new Date() : session.getCreateTime());
        }
        session.setEndTime(new Date());
        interviewSessionRepository.save(session);
    }

    @Override
    public void abandonActiveSessions(Long userId) {
        List<InterviewSession> sessions = interviewSessionRepository.findByUserIdAndStatusInAndDelFlagOrderByUpdateTimeDesc(
                userId,
                List.of(
                        InterviewSessionStatus.DRAFT.name(),
                        InterviewSessionStatus.RESUME_UPLOADING.name(),
                        InterviewSessionStatus.READY.name(),
                        InterviewSessionStatus.IN_PROGRESS.name()
                ),
                0
        );
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (InterviewSession session : sessions) {
            session.setStatus(InterviewSessionStatus.ABANDONED.name());
            session.setEndTime(new Date());
        }
        interviewSessionRepository.saveAll(sessions);
    }

    private org.springframework.data.domain.Page<InterviewSession> queryPage(
            Long userId,
            InterviewConversationPageReqDTO requestParam,
            Pageable pageable) {
        if (StrUtil.isNotBlank(requestParam.getKeyword())) {
            String keyword = requestParam.getKeyword().trim();
            if (StrUtil.isNotBlank(requestParam.getStatus())) {
                return interviewSessionRepository.findByUserIdAndStatusAndDelFlagAndTitleContaining(
                        userId,
                        requestParam.getStatus().trim(),
                        0,
                        keyword,
                        pageable
                );
            }
            return interviewSessionRepository.findByUserIdAndDelFlagAndTitleContaining(userId, 0, keyword, pageable);
        }
        if (StrUtil.isNotBlank(requestParam.getStatus())) {
            return interviewSessionRepository.findByUserIdAndStatusAndDelFlagOrderByUpdateTimeDesc(
                    userId,
                    requestParam.getStatus().trim(),
                    0,
                    pageable
            );
        }
        return interviewSessionRepository.findByUserIdAndDelFlagOrderByUpdateTimeDesc(userId, 0, pageable);
    }

    private InterviewConversationRespDTO toRespDTO(InterviewSession session) {
        InterviewConversationRespDTO respDTO = new InterviewConversationRespDTO();
        BeanUtils.copyProperties(session, respDTO);
        return respDTO;
    }
}
