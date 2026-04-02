package com.naukri.bot.message;

import com.naukri.bot.config.NaukriProperties;
import com.naukri.bot.model.Job;
import com.naukri.bot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ExternalApplyLinkSender {
    private final TelegramService telegramService;
    private StringBuilder sb = new StringBuilder("");
    private final NaukriProperties config;

    public void sendExternalApplyLinks(List<Job> jobs) {

        int jobCounter = 0;
        StringBuilder sb = new StringBuilder();

        sb.append("🚀 <b>External Apply Links</b>\n\n");

        for (Job job : jobs) {

            if (job.getExternalApplyUrl() == null || job.getExternalApplyUrl().isBlank()) {
                continue;
            }

            double score = job.getScore() / 10.0;
            int minScore = (int) config.getScoring().getMinScoreThreshold() / 10;
            String emoji = score >= minScore ? "🔥" : score >= 5 ? "⚠️" : "❌";

            sb.append(String.format(
                    "🏢 <b>%s</b>\n" +
                            "📊 Exp : %s\n" +
                            "⭐ %s %.1f/10\n" +
                            "🔗 <a href=\"%s\">Apply</a>\n\n",
                    escape(job.getCompany()),
                    escape(job.getExperienceRequired()),
                    emoji,
                    score,
                    escapeUrl(job.getExternalApplyUrl()))
            );

            jobCounter++;

            if (jobCounter == 10) {
                telegramService.send(sb.toString());

                sb.setLength(0); // reset builder
                sb.append("🚀 <b>External Apply Links</b>\n\n");

                jobCounter = 0;
            }
        }
        if (jobCounter > 0) {
            telegramService.send(sb.toString());
        }
    }

    private String escape(String input) {
        return input == null ? "" :
                input.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
    }

    private String escapeUrl(String url) {
        return url == null ? "" : url.replace("&", "&amp;");
    }
}