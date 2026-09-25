package com.konexio.bank.notification.config;

import com.resend.Resend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the Resend client, only when it is the configured provider. */
@Configuration(proxyBeanMethods = false)
class ResendClientConfig {

    @Bean
    @ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "resend")
    Resend resend(NotificationProperties properties) {
        String apiKey = properties.email().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.notification.email.provider is 'resend' but app.notification.email.api-key is not set");
        }
        return new Resend(apiKey);
    }
}
