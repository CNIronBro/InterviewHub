package com.ironbro.interviewhub.interview.flow.extraction;

import org.springframework.stereotype.Component;

/**
 * Default provider used when no knowledge base adapter is configured.
 */
@Component
public class NoopInterviewQuestionKnowledgeProvider implements InterviewQuestionKnowledgeProvider {

    @Override
    public String retrieve(String sessionId, String ragQuery) {
        return "";
    }
}
