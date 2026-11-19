package com.sellerdesk.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async}: such methods run on a background thread pool (Spring Boot's
 * {@code applicationTaskExecutor}) and the caller does not wait for them.
 *
 * <p>Used for sending email, so a slow or failing mail server never slows down or breaks an
 * HTTP request. Like scheduled jobs, async code has no TenantContext and no logged-in user:
 * pass everything it needs as parameters.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
