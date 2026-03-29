package com.naukri.bot.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContactExtractor {

    // ✅ Email regex (standard)
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    // ✅ Improved phone regex (handles +91, spaces, dashes, 0 prefix, etc.)
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?:\\+91[-\\s]?|0)?[6-9]\\d{4}[-\\s]?\\d{5}");

    public static Map<String, List<String>> extractContacts(String jobDescription) {

        Map<String, List<String>> result = new HashMap<>();
        Set<String> emails = new HashSet<>();
        Set<String> phones = new HashSet<>();

        if (jobDescription == null || jobDescription.isBlank()) {
            result.put("emails", new ArrayList<>());
            result.put("phones", new ArrayList<>());
            return result;
        }

        // 🔍 Extract Emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(jobDescription);
        while (emailMatcher.find()) {
            String email = emailMatcher.group().toLowerCase().trim();

            // Optional filter: skip fake emails
            if (!email.contains("example")) {
                emails.add(email);
            }
        }

        // 🔍 Extract Phones
        Matcher phoneMatcher = PHONE_PATTERN.matcher(jobDescription);
        while (phoneMatcher.find()) {
            String rawPhone = phoneMatcher.group();

            // 🔧 Normalize (remove spaces, dashes, +91, etc.)
            String normalized = rawPhone.replaceAll("[^0-9]", "");

            // Remove country code if present
            if (normalized.startsWith("91") && normalized.length() > 10) {
                normalized = normalized.substring(normalized.length() - 10);
            }

            // Final validation (Indian mobile)
            if (normalized.length() == 10 && normalized.matches("[6-9]\\d{9}")) {
                phones.add(normalized);
            }
        }

        result.put("emails", new ArrayList<>(emails));
        result.put("phones", new ArrayList<>(phones));

        return result;
    }
}