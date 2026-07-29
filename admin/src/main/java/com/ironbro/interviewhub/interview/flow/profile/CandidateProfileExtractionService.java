package com.ironbro.interviewhub.interview.flow.profile;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.ironbro.interviewhub.agent.application.BusinessAgentResolver;
import com.ironbro.interviewhub.agent.application.BusinessAgentScene;
import com.ironbro.interviewhub.agent.dao.entity.AgentPropertiesDO;
import com.ironbro.interviewhub.interview.application.guard.core.InterviewAiGuardStage;
import com.ironbro.interviewhub.interview.service.CandidateProfileParser;
import com.ironbro.interviewhub.interview.service.InterviewQuestionService;
import com.ironbro.interviewhub.interview.service.model.CandidateProfile;
import com.ironbro.interviewhub.interview.shared.InterviewAiInvoker;
import com.ironbro.interviewhub.interview.shared.InterviewResponseParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateProfileExtractionService {

    private static final String PROFILE_PROMPT =
            "Extract a structured candidate profile from the uploaded resume. Return JSON only.";

    private final BusinessAgentResolver businessAgentResolver;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewResponseParser interviewResponseParser;
    private final CandidateProfileParser candidateProfileParser;
    private final InterviewQuestionService interviewQuestionService;

    public CandidateProfile extract(
            String sessionId,
            String userName,
            String resumeFileUrl) throws Exception {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(resumeFileUrl)) {
            throw new IllegalArgumentException("sessionId and resumeFileUrl must not be blank");
        }
        AgentPropertiesDO agent =
                businessAgentResolver.resolveRequired(BusinessAgentScene.RESUME_PROFILE_EXTRACTION);
        log.info("Starting candidate profile extraction, scene={}, flowId={}, sessionId={}",
                BusinessAgentScene.RESUME_PROFILE_EXTRACTION.getCode(), agent.getApiFlowId(), sessionId);
        String response = interviewAiInvoker.callAiSyncWithFile(
                PROFILE_PROMPT,
                sessionId,
                agent,
                resumeFileUrl,
                InterviewAiGuardStage.RESUME_PROFILE_EXTRACTION,
                interviewAiInvoker.buildSingleFlightKey(
                        InterviewAiGuardStage.RESUME_PROFILE_EXTRACTION,
                        sessionId,
                        DigestUtil.sha256Hex(resumeFileUrl))
        );
        String content = interviewResponseParser.extractContentFromInterviewResponse(response);
        Map<String, Object> payload = interviewResponseParser.extractStructuredResult(
                content, "skills", "skillEvidence", "roleHypotheses",
                "score", "resumeSuggest", "resumeQuestion", "resumeType", "ragQuery");
        CandidateProfile profile = candidateProfileParser.parse(payload);
        interviewQuestionService.saveCandidateProfile(
                sessionId, userName, agent.getId(), resumeFileUrl, JSON.toJSONString(profile));
        log.info("Candidate profile extraction completed, scene={}, flowId={}, sessionId={}, roleHypothesisCount={}",
                BusinessAgentScene.RESUME_PROFILE_EXTRACTION.getCode(), agent.getApiFlowId(),
                sessionId, profile.getRoleHypotheses().size());
        return profile;
    }
}
