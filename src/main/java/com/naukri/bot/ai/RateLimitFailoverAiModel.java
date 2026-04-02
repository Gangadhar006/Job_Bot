package com.naukri.bot.ai;

import com.naukri.bot.config.properties.GeminiGenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitFailoverAiModel {
    private final ChatClient chatClient;
    private final GeminiGenAiProperties config;

    public String callWithFallback(String prompt) {

        List<String> models = List.of(
                "gemini-2.5-flash-lite",
                "gemini-flash-lite-latest",
                "gemini-3.1-flash-lite-preview",
                "gemini-2.0-flash-lite",
                "gemini-2.0-flash",
                "gemini-2.5-flash",
                "gemini-flash-latest",
                "gemini-3-flash-preview"
        );

        for (String model : models) {
            try {
                String response = chatClient.prompt()
                        .user(prompt)
                        .options(GoogleGenAiChatOptions.builder()
                                .model(model)
                                .temperature(config.getTemperature())
                                .maxOutputTokens(config.getMaxTokens())
                                .build()
                        )
                        .call()
                        .content();
                if (response != null && !response.isBlank()) {
                    log.info("✅ Used model: {}", model);
                    return response;
                }

            } catch (Exception ex) {
                if (isRateLimit(ex)) {
                    log.warn("⚠️ {} rate limited → trying next", model);
                    continue;
                }
                throw ex;
            }
        }

        throw new RuntimeException("❌ All models failed");
    }

    private boolean isRateLimit(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && (
                msg.contains("429") ||
                        msg.toLowerCase().contains("rate")
        );
    }
}
