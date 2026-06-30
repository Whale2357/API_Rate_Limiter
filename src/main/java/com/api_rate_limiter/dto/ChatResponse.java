package com.api_rate_limiter.dto;

public class ChatResponse {

    private final String answer;

    public ChatResponse(String answer) {
        this.answer = answer;
    }

    public String getAnswer() {
        return answer;
    }
}
