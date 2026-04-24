package com.spectrometer.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /**
     * Shared RestTemplate bean for HTTP calls (used by SpectrometerController
     * to proxy training requests to the Python AI server).
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
