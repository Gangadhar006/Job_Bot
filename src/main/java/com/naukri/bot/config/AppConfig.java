package com.naukri.bot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.Random;

@Configuration
public class AppConfig {
    @Bean
    public ObjectMapper mapper(){
        return new ObjectMapper();
    }

    @Bean
    public Random random(){
        return new Random();
    }
    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}
