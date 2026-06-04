package com.ironbro.interviewhub.common.config.database;

import com.alibaba.druid.filter.stat.StatFilter;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库持久层配置类
 */
@Slf4j
@Configuration(value = "dataBaseConfigurationByAdmin")
public class DataBaseConfiguration {

    /**
     * 分页插件
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * Druid SQL 监控过滤器
     */
    @Bean
    public FilterRegistrationBean<StatFilter> druidStatFilter() {
        FilterRegistrationBean<StatFilter> bean = new FilterRegistrationBean<>(new StatFilter());
        bean.addUrlPatterns("/*");
        bean.addInitParameter("slowSqlMillis", "1000");
        bean.addInitParameter("logSlowSql", "true");
        return bean;
    }

    @PostConstruct
    public void init() {
        log.info("MyBatis-Plus 分页插件和 Druid 监控已初始化");
    }
}
