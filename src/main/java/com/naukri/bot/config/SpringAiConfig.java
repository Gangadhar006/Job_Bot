package com.naukri.bot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a fully configured ChatClient bean backed by Anthropic Claude.
 * <p>
 * Spring AI auto-configures AnthropicChatModel from application.yml:
 * spring.ai.anthropic.api-key
 * spring.ai.anthropic.chat.options.model
 * spring.ai.anthropic.chat.options.max-tokens
 * spring.ai.anthropic.chat.options.temperature
 * <p>
 * ChatClient is the high-level fluent API — preferred over
 * injecting ChatModel directly.
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }
}