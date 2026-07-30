package com.ironbro.interviewhub.interview.flow.extraction;

/**
 * Optional knowledge enhancement port for interview question generation.
 *
 * <p>The core question workflow must never depend on a knowledge source being
 * configured or available. Implementations may query XingChen RAG, a local
 * vector store, or another question bank. Returning blank content means that
 * question generation continues with the candidate profile only.</p>
 */
public interface InterviewQuestionKnowledgeProvider {

    String retrieve(String sessionId, String ragQuery) throws Exception;
}
