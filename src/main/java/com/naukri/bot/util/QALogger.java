package com.naukri.bot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naukri.bot.model.Job;
import com.naukri.bot.model.QAEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Component
@Slf4j
public class QALogger {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void appendQa(Job job, String question, String answer) {
        try {
            List<QAEntry> history;

            if (job.getQaHistory() != null && !job.getQaHistory().isBlank()) {
                history = objectMapper.readValue(
                        job.getQaHistory(),
                        new TypeReference<List<QAEntry>>() {
                        }
                );
            } else {
                history = new ArrayList<>();
            }

            history.add(new QAEntry(question, answer));

            job.setQaHistory(objectMapper.writeValueAsString(history));

        } catch (Exception e) {
            log.error("❌ Failed to update QA history: {}", e.getMessage());
        }
    }
}