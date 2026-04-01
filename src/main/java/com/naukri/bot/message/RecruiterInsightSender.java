package com.naukri.bot.message;

import com.naukri.bot.model.Job;
import com.naukri.bot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RecruiterInsightSender {

    private final TelegramService service;

    public void sendRecruiterInsights(List<Job> jobs) {

        for (Job job : jobs) {
            if (!hasRecruiterInfo(job)) continue;
            formatMessage(job);
        }
    }

    private void formatMessage(Job job) {
        StringBuilder sb = new StringBuilder();
        sb.append("📞 <b>Recruiter Insights</b>\n\n");

        double score = job.getScore() / 10.0;
        String emoji = score >= 8 ? "🔥" : score >= 6 ? "⚠️" : "❌";

        sb.append(String.format(
                "<pre>" +
                        "🏢 %s (%s)\n" +
                        "-----------------------------\n" +
                        "📍 Location: %s\n" +
                        "📊 Exp   : %s\n" +
                        "⭐ Score : %s %.1f/10\n" +
                        "📅 Posted: %s\n" +
                        "-----------------------------\n" +
                        "📧 %s\n" +
                        "📱 %s\n" +
                        "</pre>\n\n",

                job.getCompany(),
                job.getTitle(),
                job.getLocation(),
                job.getExperienceRequired(),
                emoji,
                score,
                formatDate(job),
                job.getEmails() == null ? "-" : job.getEmails(),
                job.getPhones() == null ? "-" : job.getPhones()
        ));
        service.send(sb.toString());
    }

    private boolean hasRecruiterInfo(Job job) {
        return (job.getEmails() != null && !job.getEmails().isBlank()) ||
                (job.getPhones() != null && !job.getPhones().isBlank());
    }

    private String formatDate(Job job) {
        return job.getPostedDate() == null
                ? "-"
                : job.getPostedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM"));
    }
}