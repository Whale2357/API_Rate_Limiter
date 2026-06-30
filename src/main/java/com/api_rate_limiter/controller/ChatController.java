package com.api_rate_limiter.controller;

import com.api_rate_limiter.dto.ChatRequest;
import com.api_rate_limiter.dto.ChatResponse;
import com.api_rate_limiter.service.AiService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V5: 트래픽 제어(Filter/Interceptor)를 통과한 요청만 도달하는 Mock 비즈니스 엔드포인트.
 */
@RestController
@RequestMapping("/v1")
public class ChatController {

    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody(required = false) ChatRequest request) {
        String message = request != null ? request.getMessage() : null;
        return aiService.generateAnswer(message);
    }
}
