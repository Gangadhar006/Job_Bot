package com.naukri.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "telegram.bot")
@Data
@Component
public class TelegramConfig {
    private String token;
    private String chatId;
}
