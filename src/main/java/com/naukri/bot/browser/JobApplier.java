package com.naukri.bot.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.naukri.bot.BotProcessMonitor;
import com.naukri.bot.TestChatbotHandler;
import com.naukri.bot.ai.QuestionAnswerService;
import com.naukri.bot.config.NaukriConfig;
import com.naukri.bot.model.Job;
import com.naukri.bot.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobApplier {

    private final QuestionAnswerService qaService;
    private final NaukriConfig config;
    private final JobRepository jobRepository;
    private final Random random;
    private final TestChatbotHandler chatbotHandler;

    public int applyToAll(BrowserSession session, List<Job> queuedJobs) {

        int applied = 0;
        int cap = config.getApply().getMaxApplicationsPerSession();

        log.info("🚀 Starting apply loop — {} jobs queued, cap: {}", queuedJobs.size(), cap);

        for (Job job : queuedJobs) {
            if (applied >= cap) {
                log.info("🛑 Session cap ({}) reached — stopping", cap);
                break;
            }

            log.info("");
            log.info("── Applying [{}/{}] {} @ {} ──────────────────", applied + 1, cap, job.getTitle(), job.getCompany());

            try {
                boolean success = applySingleJob(session, job);
                if (success) {
                    applied++;
                    job.setStatus(Job.ApplicationStatus.APPLIED);
                    job.setAppliedAt(LocalDateTime.now());
                } else {
                    job.setStatus(Job.ApplicationStatus.FAILED);
                }
            } catch (Exception e) {
                log.error("   ❌ Apply failed: {}", e.getMessage());
                job.setStatus(Job.ApplicationStatus.FAILED);
                job.setFailureReason(e.getMessage());
            }

            jobRepository.save(job);
            randomDelay(config.getApply().getApplyDelayMin(),
                    config.getApply().getApplyDelayMax());
        }

        log.info("✅ Apply loop done — applied: {}", applied);
        return applied;
    }

    private boolean applySingleJob(BrowserSession session, Job job) throws Exception {
        log.info("**********************************************Monitor per job**********************************************");
        BotProcessMonitor.printBotProcesses();
        Page page = session.getPage();

        page.navigate(job.getJobUrl());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        randomDelay(2000, 3500);

        if (isAlreadyApplied(page)) {
            log.info("   ℹ️ Already applied — skipping");
            job.setStatus(Job.ApplicationStatus.APPLIED);
            return false;
        }

        Locator applyBtn = findApplyButton(page);
        if (applyBtn == null) {
            log.warn("   ⚠️ No apply button found");
            job.setFailureReason("No apply button found");
            return false;
        }
        Page newPage = null;
        try {
            newPage = page.waitForPopup(
                    new Page.WaitForPopupOptions().setTimeout(3000),
                    () -> humanClick(page, applyBtn)
            );
        } catch (Exception ex) {
            log.info("No popup opened on apply click, continuing with the same page");
        }

        randomDelay(2000, 3500);

        ApplyType applyType = detectApplyType(page, newPage, job);
        log.info("   📋 Apply type: {}", applyType);

        return switch (applyType) {
            case EASY_APPLY -> handleEasyApply(page, job);
            case FULL_FORM -> handleFullForm(page, job);
            case EXTERNAL -> handleExternal(job);
            case ALREADY_DONE -> true;
            case UNKNOWN -> {
                log.warn("   ⚠️ Unknown apply type — skipping");
                job.setFailureReason("Unknown apply form type");
                yield false;
            }
        };
    }

    private enum ApplyType {
        EASY_APPLY, FULL_FORM, EXTERNAL, ALREADY_DONE, UNKNOWN
    }

    private ApplyType detectApplyType(Page mainPage, Page newPage, Job job) {
        randomDelay(1000, 2000);

        if (newPage != null) {
            String url = newPage.url().toLowerCase();
            log.info("Popup URL: {}", url);

            if (!url.contains(".naukri.com")) {
                newPage.close();
                mainPage.bringToFront();
                job.setExternalApplyUrl(url);
                job.setFailureReason("External application site: " + url);
                return ApplyType.EXTERNAL;
            }
            newPage.close();
        }
        Page page = mainPage;

        Locator applied = page.locator("div.job-title-text:has-text('Applied to')");
        if (applied.count() > 0 && applied.first().isVisible()) {
            return ApplyType.ALREADY_DONE;
        }

        if (page.locator("[class*='chatbot_DrawerContentWrapper']").isVisible()) {
            log.info("Chatbot detected → EASY APPLY");
            return ApplyType.EASY_APPLY;
        }

        if (page.locator(".apply-form").count() > 0 ||
                page.locator("[class*='application-form']").count() > 0 ||
                page.locator("form[class*='apply']").count() > 0) {
            return ApplyType.FULL_FORM;
        }

        return ApplyType.UNKNOWN;
    }

    private boolean handleEasyApply(Page page, Job job){

        log.info("   ⚡ Handling Easy Apply...");
        chatbotHandler.handleChatbot(page, job);
//        handleResumeUpload(page);

//        handleQuestionsInContainer(page, ".apply-popup", job);
//        handleQuestionsInContainer(page, ".chatbot-container", job);
//        handleQuestionsInContainer(page, "[class*='apply-modal']", job);

        return true;
//        return clickSubmit(page, job);
    }

    private boolean handleFullForm(Page page, Job job) throws Exception {

        log.info("   📝 Handling Full Form...");
        int maxSteps = 8;

        for (int step = 1; step <= maxSteps; step++) {
            log.info("   Step {}/{}", step, maxSteps);

            handleResumeUpload(page);

            handleQuestionsInContainer(page, "body", job);

            Locator nextBtn = findNextButton(page);
            if (nextBtn != null) {
                humanClick(page, nextBtn);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                randomDelay(1500, 2500);
                continue;
            }

            boolean submitted = clickSubmit(page, job);
            if (submitted) return true;

            break;
        }

        return isSubmitConfirmed(page);
    }

    private boolean handleExternal(Job job) {
        job.setStatus(Job.ApplicationStatus.NEEDS_REVIEW);
        return false;
    }

    private void handleResumeUpload(Page page) {
        try {
            Locator fileInput = page.locator("input[type='file']");
            if (fileInput.count() > 0) {
                String resumePath = config.getApply().getResumePath();
                fileInput.first().setInputFiles(Paths.get(resumePath));
                log.info("   📎 Resume uploaded from {}", resumePath);
                randomDelay(1500, 2500);
                return;
            }

            Locator uploadBtn = page.locator(
                    "button:has-text('Upload'), " +
                            "button:has-text('Change Resume'), " +
                            "label:has-text('Upload')"
            );
            if (uploadBtn.count() > 0) {
                Locator hiddenInput = page.locator("input[type='file']");
                if (hiddenInput.count() > 0) {
                    hiddenInput.first().setInputFiles(
                            Paths.get(config.getApply().getResumePath()));
                    randomDelay(1500, 2500);
                }
            }
        } catch (Exception e) {
            log.warn("   ⚠️ Resume upload skipped: {}", e.getMessage());
        }
    }

    private void handleQuestionsInContainer(Page page,
                                            String containerSel,
                                            Job job) {
        try {
            Locator container = page.locator(containerSel).first();
            if (container.count() == 0) return;

            handleTextInputs(page, container, job);
            handleTextareas(page, container, job);
            handleDropdowns(page, container, job);
            handleRadioButtons(page, container, job);
            handleCheckboxes(page, container, job);

        } catch (Exception e) {
            log.warn("   ⚠️ Question handler error: {}", e.getMessage());
        }
    }

    private void handleTextInputs(Page page, Locator container, Job job) {

        Locator inputs = container.locator(
                "input[type='text']:visible, input[type='number']:visible, " +
                        "input[type='tel']:visible, input[type='email']:visible"
        );

        for (int i = 0; i < inputs.count(); i++) {
            try {
                Locator input = inputs.nth(i);
                if (isAlreadyFilled(input)) continue;

                String label = getLabel(page, input);
                String labelLow = label.toLowerCase();
                String answer;

                if (matches(labelLow, "total experience", "years of experience", "experience in years")) {
                    answer = qaService.getTotalExperience();
                } else if (matches(labelLow, "current ctc", "current salary", "present ctc")) {
                    answer = qaService.getCurrentCtc();
                } else if (matches(labelLow, "expected ctc", "expected salary", "ctc expectation")) {
                    answer = qaService.getExpectedCtc();
                } else if (matches(labelLow, "notice period", "serving notice")) {
                    answer = qaService.getNoticePeriod();
                } else if (matches(labelLow, "current company", "present employer")) {
                    answer = qaService.getCurrentCompany();
                } else if (matches(labelLow, "current designation", "current role", "job title")) {
                    answer = qaService.getCurrentDesignation();
                } else if (matches(labelLow, "college", "university", "institute")) {
                    answer = qaService.getCollege();
                } else if (matches(labelLow, "passing year", "graduation year", "year of passing")) {
                    answer = qaService.getPassingYear();
                } else {
                    answer = qaService.answerFreeText(label, job);
                }

                clearAndType(page, input, answer);
                randomDelay(300, 700);

            } catch (Exception e) {
                log.warn("   ⚠️ Text input error at index {}: {}", i, e.getMessage());
            }
        }
    }

    private void handleTextareas(Page page, Locator container, Job job) {

        Locator textareas = container.locator("textarea:visible");

        for (int i = 0; i < textareas.count(); i++) {
            try {
                Locator textarea = textareas.nth(i);
                if (isAlreadyFilled(textarea)) continue;

                String label = getLabel(page, textarea);
                String answer = qaService.answerFreeText(label, job);
                clearAndType(page, textarea, answer);
                randomDelay(400, 800);

            } catch (Exception e) {
                log.warn("   ⚠️ Textarea error at index {}: {}", i, e.getMessage());
            }
        }
    }

    private void handleDropdowns(Page page, Locator container, Job job) {

        Locator selects = container.locator("select:visible");

        for (int i = 0; i < selects.count(); i++) {
            try {
                Locator select = selects.nth(i);
                String label = getLabel(page, select);
                String labelLw = label.toLowerCase();

                // Collect all options
                List<String> options = new ArrayList<>();
                Locator optEls = select.locator("option");
                for (int j = 0; j < optEls.count(); j++) {
                    String txt = optEls.nth(j).innerText().trim();
                    if (!txt.isBlank() && !txt.equals("Select")) options.add(txt);
                }
                if (options.isEmpty()) continue;

                String chosen;

                // Structured fields
                if (matches(labelLw, "notice period")) {
                    chosen = findClosestOption(options, qaService.getNoticePeriod());
                } else if (matches(labelLw, "highest qualification", "education")) {
                    chosen = findClosestOption(options, qaService.getHighestQualification());
                } else if (matches(labelLw, "gender")) {
                    chosen = findClosestOption(options, config.getApply().getProfile().getGender());
                } else if (matches(labelLw, "relocat")) {
                    chosen = config.getApply().getProfile().isWillingToRelocate()
                            ? findContains(options, "yes") : findContains(options, "no");
                } else {
                    // AI picks
                    chosen = qaService.pickBestOption(label, options, job);
                }

                select.selectOption(chosen);
                log.info("   📋 Dropdown '{}' → '{}'", truncate(label, 40), chosen);
                randomDelay(300, 600);

            } catch (Exception e) {
                log.warn("   ⚠️ Dropdown error at index {}: {}", i, e.getMessage());
            }
        }
    }

    private void handleRadioButtons(Page page, Locator container, Job job) {

        // Group radios by name attribute
        Locator radios = container.locator("input[type='radio']:visible");
        List<String> processedNames = new ArrayList<>();

        for (int i = 0; i < radios.count(); i++) {
            try {
                Locator radio = radios.nth(i);
                String name = radio.getAttribute("name");
                if (name == null || processedNames.contains(name)) continue;
                processedNames.add(name);

                // Get all options in this group
                Locator group = container.locator("input[type='radio'][name='" + name + "']");
                List<String> options = new ArrayList<>();
                for (int j = 0; j < group.count(); j++) {
                    String label = getRadioLabel(page, group.nth(j));
                    if (!label.isBlank()) options.add(label);
                }
                if (options.isEmpty()) continue;

                // Question text = label of the group
                String question = name.replace("-", " ").replace("_", " ");
                String chosen;

                // Structured fields
                if (matches(question.toLowerCase(), "relocat")) {
                    chosen = config.getApply().getProfile().isWillingToRelocate()
                            ? findContains(options, "yes") : findContains(options, "no");
                } else if (matches(question.toLowerCase(), "gender")) {
                    chosen = findClosestOption(options,
                            config.getApply().getProfile().getGender());
                } else {
                    chosen = qaService.pickBestOption(question, options, job);
                }

                // Click the matching radio
                for (int j = 0; j < group.count(); j++) {
                    String lbl = getRadioLabel(page, group.nth(j));
                    if (lbl.equalsIgnoreCase(chosen)) {
                        humanClick(page, group.nth(j));
                        log.info("   🔘 Radio '{}' → '{}'", truncate(question, 40), chosen);
                        break;
                    }
                }
                randomDelay(200, 500);

            } catch (Exception e) {
                log.warn("   ⚠️ Radio error at index {}: {}", i, e.getMessage());
            }
        }
    }

    private void handleCheckboxes(Page page, Locator container, Job job) {

        Locator checkboxes = container.locator("input[type='checkbox']:visible");

        for (int i = 0; i < checkboxes.count(); i++) {
            try {
                Locator checkbox = checkboxes.nth(i);
                if (checkbox.isChecked()) continue;

                String label = getLabel(page, checkbox);
                String labelLow = label.toLowerCase();

                // Always accept terms
                if (matches(labelLow, "terms", "agree", "consent", "privacy")) {
                    checkbox.check();
                    log.info("   ☑ Checkbox '{}' → checked (terms)", truncate(label, 40));
                    continue;
                }

                // AI decides
                boolean shouldCheck = qaService.answerYesNo(label, job);
                if (shouldCheck) {
                    checkbox.check();
                    log.info("   ☑ Checkbox '{}' → checked", truncate(label, 40));
                }
                randomDelay(200, 400);

            } catch (Exception e) {
                log.warn("   ⚠️ Checkbox error at index {}: {}", i, e.getMessage());
            }
        }
    }

    private boolean clickSubmit(Page page, Job job) {
        try {
            Locator submitBtn = page.locator(
                    "button:has-text('Submit'), " +
                            "button:has-text('Apply'), " +
                            "button:has-text('Send Application'), " +
                            "button:has-text('Confirm'), " +
                            "input[type='submit']"
            ).first();

            if (submitBtn.count() == 0) {
                log.warn("   ⚠️ No submit button found");
                return false;
            }

            humanClick(page, submitBtn);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            randomDelay(2000, 3500);

            boolean confirmed = isSubmitConfirmed(page);
            if (confirmed) {
                log.info("   ✅ Application submitted successfully");
            } else {
                log.warn("   ⚠️ Submit clicked but confirmation not detected");
            }
            return confirmed;

        } catch (Exception e) {
            log.error("   ❌ Submit failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isSubmitConfirmed(Page page) {
        return page.locator("text=Application submitted").count() > 0 ||
                page.locator("text=Successfully applied").count() > 0 ||
                page.locator("text=applied successfully").count() > 0 ||
                page.locator("[class*='success-message']").count() > 0 ||
                page.locator("[class*='applied-status']").count() > 0 ||
                page.locator(".apply-success").count() > 0;
    }

    private Locator findApplyButton(Page page) {
        String[] selectors = {
                "button:has-text('Apply')",
                "button[class*='apply']",
                "a:has-text('Apply Now')",
                ".apply-button",
                "[class*='applyBtn']"
        };
        for (String sel : selectors) {
            Locator loc = page.locator(sel).first();
            if (loc.count() > 0 && loc.isVisible()) return loc;
        }
        return null;
    }

    private Locator findNextButton(Page page) {
        String[] selectors = {
                "button:has-text('Next')",
                "button:has-text('Continue')",
                "button:has-text('Proceed')",
                "button[class*='next']"
        };
        for (String sel : selectors) {
            Locator loc = page.locator(sel).first();
            if (loc.count() > 0 && loc.isVisible()) return loc;
        }
        return null;
    }

    private boolean isAlreadyApplied(Page page) {
        return page.locator("text=Applied").count() > 0 ||
                page.locator("[class*='applied']").count() > 0 ||
                page.locator("text=You have already applied").count() > 0;
    }

    private boolean isAlreadyFilled(Locator input) {
        try {
            String val = input.inputValue();
            return val != null && !val.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds the label for an input by:
     * 1. aria-label attribute
     * 2. placeholder attribute
     * 3. Associated <label> element via id/for
     * 4. Closest parent text
     */
    private String getLabel(Page page, Locator input) {
        try {
            String aria = input.getAttribute("aria-label");
            if (aria != null && !aria.isBlank()) return aria;

            String placeholder = input.getAttribute("placeholder");
            if (placeholder != null && !placeholder.isBlank()) return placeholder;

            String id = input.getAttribute("id");
            if (id != null && !id.isBlank()) {
                Locator label = page.locator("label[for='" + id + "']");
                if (label.count() > 0) return label.first().innerText().trim();
            }

            String name = input.getAttribute("name");
            if (name != null) return name.replace("-", " ").replace("_", " ");

        } catch (Exception ignored) {
        }
        return "unknown field";
    }

    private String getRadioLabel(Page page, Locator radio) {
        try {
            String id = radio.getAttribute("id");
            if (id != null) {
                Locator lbl = page.locator("label[for='" + id + "']");
                if (lbl.count() > 0) return lbl.first().innerText().trim();
            }
            // Sibling label
            return radio.locator("xpath=following-sibling::label[1]").innerText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String findClosestOption(List<String> options, String target) {
        if (target == null || options.isEmpty()) return options.isEmpty() ? "" : options.get(0);
        String tLow = target.toLowerCase();
        for (String opt : options) {
            if (opt.toLowerCase().contains(tLow) || tLow.contains(opt.toLowerCase())) return opt;
        }
        return options.get(0);
    }

    private String findContains(List<String> options, String keyword) {
        for (String opt : options) {
            if (opt.toLowerCase().contains(keyword.toLowerCase())) return opt;
        }
        return options.isEmpty() ? "" : options.get(0);
    }

    private boolean matches(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private void humanClick(Page page, Locator locator) {
        page.mouse().move(random(100, 600), random(100, 500));
        randomDelay(100, 300);
        locator.click();
    }

    private void clearAndType(Page page, Locator input, String text) {
        input.click();
        input.press("control+a");
        randomDelay(100, 200);
        for (char c : text.toCharArray()) {
            page.keyboard().type(String.valueOf(c));
            sleep(random(40, 110));
        }
    }

    private void randomDelay(int min, int max) {
        sleep(random(min, max));
    }

    private int random(int min, int max) {
        return random.nextInt(max - min) + min;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}