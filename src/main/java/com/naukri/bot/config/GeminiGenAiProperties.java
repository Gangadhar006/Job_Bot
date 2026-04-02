package com.naukri.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.google.genai.chat.options")
public class GeminiGenAiProperties {
    private String model;
    private int maxTokens;
    private double temperature;
}
