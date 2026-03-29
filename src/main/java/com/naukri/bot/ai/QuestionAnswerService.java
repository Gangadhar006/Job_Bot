package com.naukri.bot.ai;


import com.naukri.bot.NaukriBotApplication;
import com.naukri.bot.config.NaukriConfig;
import com.naukri.bot.model.Job;
import com.naukri.bot.util.QALogger;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses Spring AI's ChatClient (backed by Anthropic Claude) to answer
 * HR/screening questions during job application.
 * <p>
 * Structured fields (CTC, notice, exp) → answered directly from config.
 * Free text, MCQ, yes/no → answered by Claude via Spring AI.
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionAnswerService {

    private final ChatClient chatClient;
    private final NaukriConfig config;


    /**
     * Answers free-text questions using AI. Builds a prompt with candidate profile,
     * job details, and the question. If AI fails, falls back to a simple heuristic
     * answer based on keywords in the question. Always logs the Q&A for transparency.
     */
    public String answerFreeText(String question, Job job) {
        log.info("service- QAService");
        String fallback = fallbackFreeText(question);
        log.info("fallback-response: {}", fallback);
        if (fallback != null) {
            log.info("⚡ Using fallback answer: {}", fallback);
            return fallback;
        }

        try {
            String prompt = buildSystemPrompt() + "\n\n" +
                    buildFreeTextPrompt(question, job);

            String answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("   💬 Q: {} → A: {}...", truncate(question, 60), truncate(answer, 60));

            return answer != null ? answer.trim() : "";

        } catch (Exception e) {
            log.error("   ❌ AI free text failed for '{}': {}", question, e.getMessage());
            return "Please refer to my resume for details.";
        }
    }

    /**
     * Answers multiple-choice questions using AI. Builds a prompt with candidate profile,
     * job details, the question, and the options. Expects AI to reply with the exact
     * text of the best option. If AI fails or doesn't match options, falls back to
     * returning the first option (safe default). Always logs the Q&A for transparency.
     * answers mcq, dropdown, radio
     */
    public String pickBestOption(String question, List<String> options, Job job) {

        if (options == null || options.isEmpty()) return "";

        try {
            // 🔥 Gemini-style single prompt
            String prompt = buildSystemPrompt() + "\n\n" +
                    buildMcqPrompt(question, options, job) +
                    "\n\nIMPORTANT: Reply with ONLY ONE option EXACTLY as given. " +
                    "Do not explain. Do not rephrase.";

            String answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (answer == null || answer.isBlank()) {
                return options.get(0);
            }

            answer = answer.trim();

            for (String opt : options) {
                if (opt.equalsIgnoreCase(answer)) {
                    return opt;
                }
            }

            String normalizedAnswer = answer.replaceAll("[^a-zA-Z0-9 ]", "").toLowerCase();

            for (String opt : options) {
                String normalizedOpt = opt.replaceAll("[^a-zA-Z0-9 ]", "").toLowerCase();

                if (normalizedAnswer.equals(normalizedOpt) ||
                        normalizedAnswer.contains(normalizedOpt) ||
                        normalizedOpt.contains(normalizedAnswer)) {
                    return opt;
                }
            }

            log.warn("   ⚠️ Could not match '{}' to options — using first", answer);
            return options.get(0);

        } catch (Exception e) {
            log.error("   ❌ AI MCQ failed: {}", e.getMessage());
            return options.get(0);
        }
    }


    /**
     * Answers yes/no questions using AI. Builds a prompt with candidate profile,
     * job details, and the question. Expects AI to reply with ONLY 'yes' or 'no'.
     * If AI fails or doesn't reply with yes/no, defaults to 'yes' (safe choice).
     * Always logs the Q&A for transparency.
     */
    public boolean answerYesNo(String question, Job job) {
        try {
            // 🔥 Single prompt (Gemini style)
            String prompt = buildSystemPrompt() + "\n\n" +
                    buildFreeTextPrompt(question, job) +
                    "\n\nIMPORTANT: Reply with ONLY 'yes' or 'no'. No explanation.";

            String answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (answer == null || answer.isBlank()) {
                return true; // fallback safe
            }

            answer = answer.toLowerCase().trim();

            // ✅ Strict handling
            if (answer.startsWith("yes")) return true;
            if (answer.startsWith("no")) return false;

            // ⚠️ Handle weird Gemini outputs
            if (answer.contains("yes")) return true;
            if (answer.contains("no")) return false;

            log.warn("   ⚠️ Unexpected yes/no response: {}", answer);
            return true; // safe default

        } catch (Exception e) {
            log.error("   ❌ AI yes/no failed: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Builds a detailed system prompt for the AI, including the candidate's profile,
     * core skills, and hard rules about CTC and notice period. This gives the AI
     * necessary context to answer questions accurately and consistently across all
     * applications. The prompt emphasizes professionalism, conciseness, and honesty.
     */
    private String buildSystemPrompt() {
        NaukriConfig.Apply.Profile p = config.getApply().getProfile();
        NaukriConfig.Scoring s = config.getScoring();

        return """
                You are filling out a job application on behalf of a software engineer.
                Answer ONLY the question asked. Be concise and professional.
                Never mention you are an AI. Never fabricate project names or client names.
                
                ## Candidate Profile
                - Total Experience   : %1f years
                - Current Company    : %s
                - Current Role       : %s
                - Current CTC        : %.1f LPA
                - Expected CTC       : %.1f LPA
                - Notice Period      : %d days
                - Location           : %s
                - Willing to relocate: %s
                - Education          : %s from %s (%d)
                - Date of Birth      : %s
                
                ## Core Skills
                Primary   : %s
                Secondary : %s
                
                ## Hard Rules
                - Expected CTC is always %.1f LPA — never negotiate lower
                - Notice period is always %d days
                - Current CTC is always %.1f LPA
                - If question asks for numeric or factual value (experience, notice period, salary, DOB),
                  respond with ONLY the value. No explanation.
                - Keep answers under 1–2 sentences maximum.
                - Prefer short, direct answers over explanations.
                """.formatted(
                p.getTotalExperienceYears(),
                p.getCurrentCompany(),
                p.getCurrentDesignation(),
                p.getCurrentCtcLpa(),
                p.getExpectedCtcLpa(),
                p.getNoticePeriodDays(),
                p.getHometown(),
                p.isWillingToRelocate() ? "Yes" : "No",
                p.getHighestQualification(), p.getCollege(), p.getPassingYear(),
                p.getDateOfBirth(),
                String.join(", ", s.getSkills().getPrimary()),
                String.join(", ", s.getSkills().getSecondary()),
                p.getExpectedCtcLpa(),
                p.getNoticePeriodDays(),
                p.getCurrentCtcLpa()
        );
    }


    /**
     * Builds a prompt for free-text questions, including job details and the question.
     * Truncates the job description to 800 chars to fit within token limits while
     * still providing relevant context. Instructs the AI to answer professionally
     * and concisely, with a max of 100 words unless more detail is needed.
     */
    private String buildFreeTextPrompt(String question, Job job) {
        return """
                ## Job Details
                Title   : %s
                Company : %s
                JD:
                %s
                
                ## Question
                %s
                
                Answer briefly and directly.
                
                If the question expects a short value (years, notice period, salary, yes/no),
                respond with ONLY the value.
                
                Do not add explanation unless explicitly asked.
                """.formatted(
                job.getTitle(),
                job.getCompany(),
                truncate(job.getJobDescription(), 800),
                question
        );
    }

    /**
     * Builds a prompt for multiple-choice questions, including job details, the question,
     * and the options. Instructs the AI to reply with ONLY the exact text of the best
     * option, and nothing else. This helps ensure we get a clean answer that can be
     * directly matched to one of the provided options.
     */
    private String buildMcqPrompt(String question, List<String> options, Job job) {
        return """
                ## Job Details
                Title   : %s
                Company : %s
                
                ## Question
                %s
                
                ## Options
                %s
                
                Reply with ONLY the exact text of the best option. Nothing else.
                """.formatted(
                job.getTitle(),
                job.getCompany(),
                question,
                String.join("\n", options)
        );
    }

    public List<String> pickMultipleOptions(String question, List<String> options, Job job) {

        if (options == null || options.isEmpty()) return List.of();

        try {
            String prompt = buildSystemPrompt() + "\n\n" +
                    buildMcqPrompt(question, options, job) +
                    "\n\nIMPORTANT: You can select MULTIPLE options. " +
                    "Reply with comma-separated exact option texts. No explanation.";

            String answer = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (answer == null || answer.isBlank()) {
                return List.of(options.get(0));
            }

            String[] parts = answer.split(",");

            List<String> result = new ArrayList<>();

            for (String part : parts) {
                String cleaned = part.trim();

                for (String opt : options) {
                    if (opt.equalsIgnoreCase(cleaned)) {
                        result.add(opt);
                    }
                }
            }

            return result.isEmpty() ? List.of(options.get(0)) : result;

        } catch (Exception e) {
            log.error("❌ AI multi-select failed: {}", e.getMessage());
            return List.of(options.get(0));
        }
    }

    /**
     * Simple heuristic fallback for free-text questions if AI fails. Checks for keywords
     * in the question to return relevant profile info directly from config. If no
     * keywords match, returns a generic fallback directing to the resume. This ensures
     * we always have a reasonable answer even if AI is unavailable or fails to understand
     * the question.
     */
    private String fallbackFreeText(String question) {
        String q = question.toLowerCase();
        NaukriConfig.Apply.Profile p = config.getApply().getProfile();

        if (q.contains("total experience") || q.contains("total exp"))
            return getTotalExperience() + "Years";

        if (q.contains("experience")) {
            String skill = extractSkill(question);
            return getSkillExperience(skill) + " years";
        }

        if (q.contains("notice")) return p.getNoticePeriodDays() + " days";

        if (q.contains("expected salary") ||
                q.contains("expected ctc") ||
                q.contains("expected") ||
                q.contains("expected compensation") ||
                q.contains("compensation")
        )
            return p.getExpectedCtcLpa() + " LPA";

        if (q.contains("current salary") ||
                q.contains("current ctc") ||
                q.contains("current compensation") ||
                q.contains("current") ||
                q.contains("current")
        )
            return getExpectedCtc() + " LPA";

        if (q.contains("location") || q.contains("relocate") || q.contains("relocation")) return p.getHometown();
        if (q.contains("qualify") || q.contains("education")) return getHighestQualification();
        if (q.contains("birth") || q.contains("dob")) return getDateOfBirth();

        return null;
    }

    /**
     * Truncates a string to a maximum length, adding "..." if it was truncated. Returns empty string if input is null.
     */
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public String getTotalExperience() {
        return String.valueOf(config.getApply().getProfile().getTotalExperienceYears());
    }

    public String getCurrentCtc() {
        return String.valueOf(config.getApply().getProfile().getCurrentCtcLpa());
    }

    public String getExpectedCtc() {
        return String.valueOf(config.getApply().getProfile().getExpectedCtcLpa());
    }

    public String getNoticePeriod() {
        return String.valueOf(config.getApply().getProfile().getNoticePeriodDays());
    }

    public String getCurrentCompany() {
        return config.getApply().getProfile().getCurrentCompany();
    }

    public String getCurrentDesignation() {
        return config.getApply().getProfile().getCurrentDesignation();
    }

    public String getHighestQualification() {
        return config.getApply().getProfile().getHighestQualification();
    }

    public String getPassingYear() {
        return String.valueOf(config.getApply().getProfile().getPassingYear());
    }

    public String getCollege() {
        return config.getApply().getProfile().getCollege();
    }

    public String getDateOfBirth() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return config.getApply().getProfile().getDateOfBirth().format(formatter);
    }

    private String extractSkill(String question) {
        String q = question.toLowerCase();

        List<String> allSkills = new ArrayList<>();
        allSkills.addAll(config.getScoring().getSkills().getPrimary());
        allSkills.addAll(config.getScoring().getSkills().getSecondary());
        allSkills.addAll(config.getScoring().getSkills().getBonus());

        for (String skill : allSkills) {
            if (q.contains(skill.toLowerCase())) {
                return skill;
            }
        }
        return null;
    }

    private String getSkillExperience(String skill) {
        NaukriConfig.Apply.Profile p = config.getApply().getProfile();
        NaukriConfig.Scoring.Skills s = config.getScoring().getSkills();

        if (s.getPrimary().stream().anyMatch(sk -> sk.equalsIgnoreCase(skill))) {
            return p.getTotalExperienceYears() + " years";
        }

        if (s.getSecondary().stream().anyMatch(sk -> sk.equalsIgnoreCase(skill))) {
            return "2 years";
        }

        if (s.getBonus().stream().anyMatch(sk -> sk.equalsIgnoreCase(skill))) {
            return "1 year";
        }

        return "0 years";
    }
}
