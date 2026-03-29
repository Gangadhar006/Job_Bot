//package com.naukri.bot;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.stereotype.Component;
//
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class GeminiHealthCheck implements ApplicationRunner {
//
//    private final ChatClient chatClient;
//
//    @Override
//    public void run(ApplicationArguments args) {
//        try {
//            log.info("🚀 Checking Gemini AI integration...");
//
//            String response = chatClient.prompt()
//                    .user("hi mate")
//                    .call()
//                    .content();
//
//            if (response != null) {
//                log.info("✅ Gemini AI is working properly: {}",response);
//            } else {
//                log.warn("⚠️ Gemini responded unexpectedly: {}", response);
//            }
//
//        } catch (Exception e) {
//            log.error("❌ Gemini AI check FAILED: {}", e.getMessage(), e);
//        }
//    }
//}