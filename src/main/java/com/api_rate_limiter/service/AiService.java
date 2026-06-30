package com.api_rate_limiter.service;

import com.api_rate_limiter.dto.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * V5: 외부 LLM 연동 대신 고정 구조의 Mock 응답을 반환한다.
 * 본 프로젝트의 초점은 Rate Limiter 시스템 자체의 보호 능력 증명에 있다.
 */
@Service
public class AiService {

    public ChatResponse generateAnswer(String message) {
        return new ChatResponse("Mock AI Response");
    }
}
