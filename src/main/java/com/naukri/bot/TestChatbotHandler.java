package com.naukri.bot;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.naukri.bot.ai.QuestionAnswerService;
import com.naukri.bot.config.NaukriProperties;
import com.naukri.bot.model.Job;
import com.naukri.bot.repository.JobRepository;
import com.naukri.bot.util.QALogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestChatbotHandler {

    private final QuestionAnswerService qaService;
    private final JobRepository jobRepository;
    private final QALogger qaLogger;
    private final NaukriProperties config;

    // 🔥 MAIN ENTRY
    public void handleChatbot(Page page, Job job) {
        log.info("TestChat handling AI chatbot");
        int lastProcessed = 0;

        while (true) {

            Locator messages = page.locator("li.botItem.chatbot_ListItem span");
            int currentCount = messages.count();

            if (currentCount > lastProcessed) {

                String question = messages.last().innerText().trim();

                if (question.toLowerCase().contains("thank you for your responses.")) {
                    lastProcessed = currentCount;
                    continue;
                }

                log.info("🧠 Question: {}", question);

                humanDelay(question);

                boolean hasTextBox = page.locator("[contenteditable='true']").first().isVisible();
                boolean hasChips = page.locator(".chatbot_Chip").count() > 0;
                boolean hasMultiCheck = page.locator("[class*='multiselectcheckbox']").isVisible();
                boolean hasRadio = page.locator("input[type='radio']").count() > 0;

                if (hasTextBox) {
                    handleText(page, question, job);

                } else if (hasMultiCheck) {
                    handleMultiCheck(page, question, job);

                } else if (hasRadio) {
                    handleRadio(page, question, job);
                } else if (hasChips) {
                    handleChips(page, question, job);
                } else {
                    log.warn("⚠️ Unknown input type — skipping");
                }

                if (isCompleted(page)) {
                    log.info("✅ Already applied (auto submit)");
                    break;
                }

                clickSave(page);

                lastProcessed = currentCount;
            }

            if (isCompleted(page)) {
                log.info("✅ Chatbot completed");
                break;
            }

            sleep(1000);
        }
    }

    private void handleMultiCheck(Page page, String question, Job job) {

        Locator checkboxes = page.locator("input[type='checkbox']");
        int count = checkboxes.count();

        if (count == 0) return;

        String hometown = config.getApply().getProfile().getHometown().toLowerCase();
        boolean relocate = config.getApply().getProfile().isWillingToRelocate();

        log.info("☑️ Found {} checkbox options", count);

        boolean selectedAny = false; // 🔥 track selection

        for (int i = 0; i < count; i++) {

            Locator checkbox = checkboxes.nth(i);
            String value = checkbox.getAttribute("value");

            if (value == null) continue;

            String valLower = value.toLowerCase();

            // ❌ Skip this question option
            if (valLower.contains("skip")) continue;

            // 🟢 If not willing to relocate → only hometown
            if (!relocate) {
                if (hometown.contains(valLower)) {
                    page.locator("label[for='" + value + "']").click();
                    log.info("☑️ Selected hometown: {}", value);
                    selectedAny = true;

                    qaLogger.appendQa(job, question, value);
                    jobRepository.save(job);
                    break;
                }
            }

            // 🟢 If willing to relocate → select first 2–3
            if (relocate) {
                page.locator("label[for='" + value + "']").click();
                log.info("☑️ Selected: {}", value);
                selectedAny = true;

                qaLogger.appendQa(job, question, value);
                jobRepository.save(job);
                if (i >= 2) break;
            }
        }

        // =========================
        // 🤖 AI FALLBACK
        // =========================
        if (!selectedAny) {

            log.warn("⚠️ No rule-based match, using AI fallback");

            List<String> options = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String value = checkboxes.nth(i).getAttribute("value");

                if (value != null && !value.toLowerCase().contains("skip")) {
                    options.add(value);
                }
            }

            if (!options.isEmpty()) {

                List<String> chosen = qaService.pickMultipleOptions(question, options, job);

                // 🔥 safety limit
                if (chosen.size() > 3) {
                    chosen = chosen.subList(0, 3);
                }

                for (String opt : chosen) {
                    page.locator("label[for='" + opt + "']").click();
                    log.info("🤖 AI selected: {}", opt);
                }
                qaLogger.appendQa(job, question, chosen.stream().collect(Collectors.joining(", ")));
                jobRepository.save(job);
            }
        }
    }

    // =========================
// 🧩 CHIP HANDLER
// =========================
    private void handleChips(Page page, String question, Job job) {

        Locator chips = page.locator(".chatbot_Chip span");
        int count = chips.count();

        if (count == 0) return;

        List<String> options = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            options.add(chips.nth(i).innerText().trim());
        }

        log.info("🧩 Chips: {}", options);

        // 🔥 Special case: resume upload
        for (int i = 0; i < count; i++) {
            String text = options.get(i).toLowerCase();

            if (text.contains("upload resume")) {
                chips.nth(i).click();
                log.info("📎 Clicked Upload Resume");

                handleResumeUpload(page);

                page.waitForTimeout(2000);

                if (isCompleted(page)) {
                    qaLogger.appendQa(job, question, "Uploaded Resume");
                    jobRepository.save(job);
                    log.info("✅ Auto applied after resume upload");
                    return;
                }
            }
        }

        // 🔥 AI selection
        String chosen = qaService.pickBestOption(question, options, job);
        qaLogger.appendQa(job, question, chosen);
        jobRepository.save(job);

        for (int i = 0; i < count; i++) {
            if (options.get(i).equalsIgnoreCase(chosen)) {
                chips.nth(i).click();
                log.info("🧩 Selected: {}", chosen);
                return;
            }
        }

        // fallback
        chips.first().click();
        log.warn("⚠️ Fallback chip selected: {}", options.get(0));
    }

    // =========================
// 🔘 RADIO HANDLER
// =========================
    private void handleRadio(Page page, String question, Job job) {

        Locator options = page.locator("label.ssrc__label");
        int count = options.count();

        List<String> texts = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            texts.add(options.nth(i).innerText().trim());
        }

        if (texts.isEmpty()) return;

        String chosen = qaService.pickBestOption(question, texts, job);

        qaLogger.appendQa(job, question, chosen);
        jobRepository.save(job);

        for (int i = 0; i < count; i++) {
            if (texts.get(i).equalsIgnoreCase(chosen)) {
                options.nth(i).click();
                log.info("🔘 Selected: {}", chosen);
                return;
            }
        }

        // fallback
        options.first().click();
        log.warn("⚠️ Fallback selected: {}", texts.get(0));
    }

    // =========================
// ✍️ TEXT HANDLER
// =========================
    private void handleText(Page page, String question, Job job) {

        Locator input = page.locator("[contenteditable='true']").first();

        input.waitFor();
        input.click();

        String answer = qaService.answerFreeText(question, job);

        qaLogger.appendQa(job, question, answer);
        jobRepository.save(job);

        for (char c : answer.toCharArray()) {
            page.keyboard().type(String.valueOf(c));
            sleep(40);
        }

        log.info("✍️ Typed: {}", answer);
    }

    // =========================
// 📎 RESUME UPLOAD
// =========================
    private void handleResumeUpload(Page page) {
        try {
            Locator fileInput = page.locator("input[type='file']");

            if (fileInput.count() > 0) {
                fileInput.first().setInputFiles(Paths.get("C:/Users/puram/Desktop/Gangadhar_JP/test_doc.docx")); // 🔥 update path
                log.info("📎 Resume uploaded");
                sleep(2000);
                page.waitForTimeout(2000);
            }

        } catch (Exception e) {
            log.warn("⚠️ Resume upload skipped: {}", e.getMessage());
        }
    }

    // =========================
// 💾 SAVE BUTTON
// =========================
    private void clickSave(Page page) {

        Locator saveBtn = page.locator("div.sendMsg");

        page.waitForCondition(() ->
                !saveBtn.getAttribute("class").contains("disabled")
        );

        saveBtn.click();
        sleep(1000);
    }

    // =========================
// ✅ EXIT CONDITION
// =========================
    private boolean isCompleted(Page page) {
        return page.locator("text=Applied to").count() > 0
                || page.locator("text=Application submitted").count() > 0
                || page.locator("text=Successfully applied").count() > 0;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void humanDelay(String question) {
        int base = 1500; // minimum delay
        int perWord = 200; // delay per word

        int wordCount = question.split("\\s+").length;

        int delay = base + (wordCount * perWord);

        // add randomness (±500ms)
        int random = (int) (Math.random() * 1000) - 500;

        delay = Math.max(1000, delay + random);
        sleep(delay);
    }
}