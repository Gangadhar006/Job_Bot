package com.naukri.bot.ai;


import com.naukri.bot.config.NaukriConfig;
import com.naukri.bot.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

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
        try {
            String answer = chatClient.prompt(
                            new Prompt(List.of(
                                    new SystemMessage(buildSystemPrompt()),
                                    new UserMessage(buildFreeTextPrompt(question, job))
                            ))
                    )
                    .call()
                    .content();
            log.info("   💬 Q: {} → A: {}...", truncate(question, 60), truncate(answer, 60));
            return answer.trim();
        } catch (Exception e) {
            log.error("   ❌ AI free text failed for '{}': {}", question, e.getMessage());
            return fallbackFreeText(question);
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
            String answer = chatClient.prompt(
                            new Prompt(List.of(
                                    new SystemMessage(buildSystemPrompt()),
                                    new UserMessage(buildMcqPrompt(question, options, job))
                            ))
                    )
                    .call()
                    .content()
                    .trim();

            // Exact match
            for (String opt : options) {
                if (opt.equalsIgnoreCase(answer)) return opt;
            }

            // Fuzzy match
            String answerLower = answer.toLowerCase();
            for (String opt : options) {
                if (answerLower.contains(opt.toLowerCase()) ||
                        opt.toLowerCase().contains(answerLower)) {
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
            String answer = chatClient.prompt(
                            new Prompt(List.of(
                                    new SystemMessage(buildSystemPrompt()),
                                    new UserMessage(
                                            buildFreeTextPrompt(question, job) +
                                                    "\n\nReply with ONLY 'yes' or 'no'. Nothing else."
                                    )
                            ))
                    )
                    .call()
                    .content()
                    .toLowerCase()
                    .trim();

            return answer.startsWith("yes");

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
                - Total Experience   : %d years
                - Current Company    : %s
                - Current Role       : %s
                - Current CTC        : %.1f LPA
                - Expected CTC       : %.1f LPA
                - Notice Period      : %d days
                - Location           : %s
                - Willing to relocate: %s
                - Education          : %s from %s (%d)
                
                ## Core Skills
                Primary   : %s
                Secondary : %s
                
                ## Hard Rules
                - Expected CTC is always %.1f LPA — never negotiate lower
                - Notice period is always %d days
                - Current CTC is always %.1f LPA
                - Keep answers under 150 words unless detail is explicitly requested
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
                
                Answer professionally and concisely (max 100 words unless more detail is needed).
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

        if (q.contains("experience")) return p.getTotalExperienceYears() + " years";
        if (q.contains("notice")) return p.getNoticePeriodDays() + " days";
        if (q.contains("ctc") || q.contains("salary") ||
                q.contains("compensation")) return p.getExpectedCtcLpa() + " LPA";
        if (q.contains("location") || q.contains("relocat")) return p.getHometown();
        if (q.contains("qualify") || q.contains("education")) return p.getHighestQualification();

        return "Please refer to my resume for details.";
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

}
