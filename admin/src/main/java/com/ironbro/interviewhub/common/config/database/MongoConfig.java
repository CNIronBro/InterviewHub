package com.ironbro.interviewhub.common.config.database;

import com.ironbro.interviewhub.ai.dao.entity.AiMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB配置类
 * 启用审计功能，设置消息TTL索引
 */
@Configuration
@EnableMongoAuditing
@RequiredArgsConstructor
public class MongoConfig {

    private final MongoTemplate mongoTemplate;

    /**
     * 为 ai_message 集合创建 TTL 索引，90天后自动清理
     */
    @PostConstruct
    public void createTTLIndexes() {
        mongoTemplate.indexOps(AiMessage.class)
                .ensureIndex(new Index()
                        .on("createTime", Sort.Direction.ASC)
                        .expire(90, TimeUnit.DAYS));
    }
}