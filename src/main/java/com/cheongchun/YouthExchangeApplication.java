package com.cheongchun;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class YouthExchangeApplication {
    public static void main(String[] args) {
        SpringApplication.run(YouthExchangeApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WebMvcConfigurer apiCors(@Value("${app.frontend-origin}") String frontendOrigin) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (!frontendOrigin.isBlank()) {
                    registry.addMapping("/api/v1/**")
                            .allowedOrigins(frontendOrigin)
                            .allowedMethods("GET", "POST", "PUT", "DELETE")
                            .allowedHeaders("Content-Type")
                            .allowCredentials(true);
                }
            }
        };
    }
}
