package com.naukri.bot.message;

import com.naukri.bot.model.Job;
import com.naukri.bot.service.JobStorageService;
import com.naukri.bot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.text.WordUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JobInsightSender {
    private final TelegramService service;

    public void sendJobInsight(List<Job> jobs) {
        for (Job job : jobs)
            service.send(formatMessage(job));
    }

    public String formatMessage(Job job) {
        return String.format(
                "🚀 <b>New Job Alert</b>\n\n" +
                        "🏢 <b>Company:</b> %s\n" +
                        "💼 <b>Role:</b> %s\n" +
                        "📂 <b>Category:</b> %s\n\n" +
                        "📍 <b>Location:</b> %s\n" +
                        "💰 <b>Salary:</b> %s\n" +
                        "📊 <b>Experience:</b> %s\n\n" +
                        "⭐ <b>Score:</b> %.1f/10\n" +
                        "📌 <b>Status:</b> %s\n" +
                        "📅 <b>Posted Date:</b> %s\n\n" +
                        "🔗 <a href=\"%s\">Apply Now</a>\n",

                job.getCompany(),
                job.getTitle(),
                WordUtils.capitalizeFully(job.getCategory().replace("_", " ")),
                job.getLocation(),
                job.getSalaryRange(),
                job.getExperienceRequired(),
                job.getScore() / 10.0,
                job.getStatus(),
                job.getPostedDate() == null
                        ? "-"
                        : job.getPostedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM")),
                job.getExternalApplyUrl() == null ? job.getJobUrl() : job.getExternalApplyUrl()
        );
    }
}