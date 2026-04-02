package com.naukri.bot.service;

import com.naukri.bot.config.properties.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {
    private final TelegramProperties config;
    private final RestTemplate restTemplate;

    public void send(String msg) {
        try {
            Thread.sleep(1000);
            String url = "https://api.telegram.org/bot" + config.getToken() + "/sendMessage";

            Map<String, String> body = Map.of(
                    "chat_id", config.getChatId(),
                    "text", msg,
                    "parse_mode", "HTML"
            );

            restTemplate.postForObject(url, body, String.class);
        } catch (Exception ex) {
            log.warn("❌ Notification failed to send: {}", ex.getMessage());
        }
    }
}