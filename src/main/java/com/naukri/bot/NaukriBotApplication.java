package com.naukri.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class NaukriBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(NaukriBotApplication.class, args);
    }

}
