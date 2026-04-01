package com.naukri.bot.message;

import com.naukri.bot.model.Job;
import com.naukri.bot.service.JobStorageService;
import com.naukri.bot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobStatsSender {
    private final TelegramService telegramService;
    private final JobStorageService jobStorageService;

    public void sendJobStats() {
        telegramService.send(formatMessage());
    }

    private String formatMessage() {
        String message = String.format(
                "📊 <b>Job Processing Summary</b>\n\n" +
                        "<pre>" +
                        "Scraped      : %d\n" +
                        "Queued       : %d\n" +
                        "Applied      : %d ✅\n" +
                        "Failed       : %d ❌\n" +
                        "Needs Review : %d 👀\n" +
                        "--------------------------------\n" +
                        "Total in DB  : %d\n" +
                        "</pre>\n" +
                        "════════════════════════════════",

                jobStorageService.totalByStatus(Job.ApplicationStatus.DISCOVERED),
                jobStorageService.totalByStatus(Job.ApplicationStatus.QUEUED),
                jobStorageService.totalByStatus(Job.ApplicationStatus.APPLIED),
                jobStorageService.totalByStatus(Job.ApplicationStatus.FAILED),
                jobStorageService.totalByStatus(Job.ApplicationStatus.NEEDS_REVIEW),
                jobStorageService.totalJobsInDb()
        );
        return message;
    }
}