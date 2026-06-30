package com.api_rate_limiter.config;

import com.api_rate_limiter.filter.ApiKeyFilter;
import com.api_rate_limiter.interceptor.RateLimitInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * V5: 게이트웨이 계층 등록. ApiKeyFilter(인증) → RateLimitInterceptor(트래픽 제어) → Controller 순으로 통과시킨다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String PROTECTED_PATH = "/v1/**";
    private static final String PROTECTED_URL_PATTERN = "/v1/*";

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter() {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter());
        registration.addUrlPatterns(PROTECTED_URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns(PROTECTED_PATH);
    }
}
