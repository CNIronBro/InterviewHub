package com.ironbro.interviewhub.auth.infrastructure.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenAuthInterceptorConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                .match("/api/v1/**")
                .notMatch("/api/v1/users/login")
                .notMatch("/api/v1/users/register")
                .notMatch("/api/v1/users/has-username")
                .notMatch("/api/v1/users/check-login")
                .notMatch("/api/v1/ai/**")
                .check(StpUtil::checkLogin)
        )).addPathPatterns("/**");
    }
}
