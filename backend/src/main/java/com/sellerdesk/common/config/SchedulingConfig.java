package com.sellerdesk.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} background jobs, e.g. {@code ImageCleanupJob}.
 *
 * <p>Remember: scheduled jobs run outside any HTTP request, so there is no logged-in user and
 * no TenantContext. A job must pass {@code shopId} explicitly.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
