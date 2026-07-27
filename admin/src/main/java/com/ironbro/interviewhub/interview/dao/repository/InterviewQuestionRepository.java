package com.ironbro.interviewhub.interview.dao.repository;

import com.ironbro.interviewhub.interview.dao.entity.InterviewQuestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewQuestionRepository extends MongoRepository<InterviewQuestion, String> {

    Optional<InterviewQuestion> findBySessionIdAndDelFlag(String sessionId, Integer delFlag);

    List<InterviewQuestion> findByUserNameAndDelFlagOrderByCreateTimeDesc(String userName, Integer delFlag);

    Page<InterviewQuestion> findByUserNameAndDelFlagOrderByCreateTimeDesc(String userName, Integer delFlag, Pageable pageable);

    /**
     * 分页查询所有面试题
     */
    Page<InterviewQuestion> findByDelFlagOrderByCreateTimeDesc(Integer delFlag, Pageable pageable);

    /**
     * 根据面试类型查询面试题列表
     */
    List<InterviewQuestion> findByInterviewTypeAndDelFlagOrderByCreateTimeDesc(String interviewType, Integer delFlag);

    /**
     * 根据智能体ID查询面试题列表
     */
    List<InterviewQuestion> findByAgentIdAndDelFlagOrderByCreateTimeDesc(Long agentId, Integer delFlag);

    /**
     * 统计用户的面试题数量
     */
    Integer countByUserNameAndDelFlag(String userName, Integer delFlag);

    /**
     * 统计指定面试类型的数量
     */
    Integer countByInterviewTypeAndDelFlag(String interviewType, Integer delFlag);
}