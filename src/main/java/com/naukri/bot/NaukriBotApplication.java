package com.naukri.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class NaukriBotApplication {

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("🛑 Killing Chrome processes...");
                Runtime.getRuntime().exec("taskkill /F /T /IM chrome.exe");
            } catch (Exception ignored) {}
        }));
        SpringApplication.run(NaukriBotApplication.class, args);
    }
}