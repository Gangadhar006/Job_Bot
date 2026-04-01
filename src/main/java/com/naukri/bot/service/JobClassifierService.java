package com.naukri.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobClassifierService {

    private final ChatClient chatClient;

    // 🔥 Keywords
    private static final List<String> EVENT_KEYWORDS = List.of(
            "kafka", "event", "event-driven", "asynchronous",
            "pub/sub", "rabbitmq", "stream", "consumer", "producer", "queue"
    );

    private static final List<String> BACKEND_KEYWORDS = List.of(
            "java", "spring", "spring boot", "rest", "microservices",
            "hibernate", "jpa"
    );

    private static final List<String> CLOUD_KEYWORDS = List.of(
            "aws", "docker", "kubernetes", "ci/cd", "jenkins", "cloud"
    );

    private int score(String text, List<String> keywords, int weight) {
        int score = 0;
        for (String k : keywords) {
            if (text.contains(k)) {
                score += weight;
            }
        }
        return score;
    }

    private String classifyWithAI(String jd) {

        log.info("Classifying with AI...");

        try {
            String prompt = """
                    Classify the following job description into ONE of these categories ONLY:
                    
                    - EVENT_DRIVEN
                    - JAVA_BACKEND
                    - GENERAL_SDE
                    
                    Rules:
                    - Output EXACTLY one label
                    - No explanation
                    
                    JD:
                    %s
                    """.formatted(truncate(jd, 800));

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return normalize(response);

        } catch (Exception e) {
            return "GENERAL_SDE";
        }
    }

    public String classify(String jd) {

        if (jd == null || jd.isBlank()) {
            return "GENERAL_SDE";
        }

        String text = jd.toLowerCase();

        int eventScore = score(text, EVENT_KEYWORDS, 2);
        int backendScore = score(text, BACKEND_KEYWORDS, 1);
        int cloudScore = score(text, CLOUD_KEYWORDS, 1);

        log.info("⚡ Scores → EVENT={} | BACKEND={} | CLOUD={}",
                eventScore, backendScore, cloudScore);

        int maxScore = Math.max(eventScore, Math.max(backendScore, cloudScore));

        if (maxScore < 2) {
            log.info("Returned: GENERAL_SDE (weak signals)");
            return "GENERAL_SDE";
        }

        if (eventScore == maxScore) {
            log.info("Returned: EVENT_DRIVEN");
            return "EVENT_DRIVEN";
        }

        if (backendScore == maxScore) {
            log.info("Returned: JAVA_BACKEND");
            return "JAVA_BACKEND";
        }

        log.info("Returned: CLOUD_ENGINEER");
        return "CLOUD_ENGINEER";
    }

    private String normalize(String output) {

        if (output == null) return "GENERAL_SDE";

        String r = output.trim().toUpperCase();

        if (r.contains("EVENT")) return "EVENT_DRIVEN";
        if (r.contains("BACKEND") || r.contains("JAVA")) return "JAVA_BACKEND";

        return "GENERAL_SDE";
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

//    private String safeAI(String jd) {
//        try {
//            Thread.sleep(3000); // 🔥 real delay
//
//            log.info("🤖 Calling AI...");
//
//            return classifyWithAI(jd);
//
//        } catch (Exception e) {
//            log.warn("AI failed → fallback GENERAL_SDE");
//            return "GENERAL_SDE";
//        }
//    }
}
