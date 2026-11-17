package com.sellerdesk.common.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on Spring's {@code @Cacheable} support. The cache itself is Caffeine (in-memory),
 * configured in application.yml under {@code spring.cache}.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
