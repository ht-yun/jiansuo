package com.guquan.equity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.client.RestTemplate;
import com.guquan.equity.provider.GsxtAccessControlProperties;
import com.guquan.equity.provider.GsxtCaptchaProperties;
import com.guquan.equity.service.CompanyBatchService;

@SpringBootApplication
@EnableConfigurationProperties({GsxtAccessControlProperties.class, GsxtCaptchaProperties.class})
public class CompanyProfileQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompanyProfileQueryApplication.class, args);
    }

    @Bean
    ApplicationRunner reclassifyLegacyAutomationIssues(CompanyBatchService batchService) {
        return args -> batchService.reclassifyLegacyAutomationIssues();
    }

    @Bean
    RestTemplate chaojiyingRestTemplate() {
        return new RestTemplate();
    }
}
