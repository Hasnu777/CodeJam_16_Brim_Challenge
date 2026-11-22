package com.brimap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Live mailbox settings, and the timer for the optional auto-poll (MailIntakeService.poll). */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {
}
