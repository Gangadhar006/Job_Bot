package com.naukri.bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "naukri")
public class NaukriConfig {
    private Credentials credentials = new Credentials();
    private Browser browser = new Browser();
    private Session session = new Session();
    private Scraper scraper = new Scraper();
    private Scoring scoring = new Scoring();
    private Apply apply = new Apply();

    @Data
    public static class Credentials {
        private String email;
        private String password;
    }

    @Data
    public static class Browser {
        private boolean headless = false;
        private int slowMo;
    }

    @Data
    public static class Session {
        private String cookieFile = "session/naukri-cookies.json";
    }

    @Data
    public static class Scraper {
        private List<String> keywords;
        private List<String> locations;
        private int experienceMin;
        private int experienceMax;
        private int pagesPerKeyword;
        private int jdFetchDelayMin;
        private int jdFetchDelayMax;
    }

    @Data
    public static class Scoring {
        private double minScoreThreshold;
        private Skills skills;
        private int yourExperienceYears;
        private List<String> preferredLocations;
        private int minSalaryLpa;
        private List<String> blacklistedCompanies;
        private List<String> blacklistedTitleKeywords;

        @Data
        public static class Skills {
            private List<String> primary;
            private List<String> secondary;
            private List<String> bonus;
        }
    }

    @Data
    public static class Apply {
        private String resumePath;
        private int maxApplicationsPerSession;
        private int applyDelayMin;
        private int applyDelayMax;
        private Profile profile = new Profile();

        @Data
        public static class Profile {
            private int totalExperienceYears;
            private double currentCtcLpa;
            private double expectedCtcLpa;
            private int noticePeriodDays;
            private String currentCompany;
            private String currentDesignation;
            private String hometown;
            private boolean willingToRelocate;
            private String gender;
            private String highestQualification;
            private int passingYear;
            private String college;
        }
    }
}
