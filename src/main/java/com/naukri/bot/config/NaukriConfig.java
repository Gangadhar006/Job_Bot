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
    private Scraper scraper = new Scraper();   // ← NEW

    @Data
    public static class Credentials {
        private String email;
        private String password;
    }

    @Data
    public static class Browser {
        private boolean headless = false;
        private int slowMo = 50;
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
}
