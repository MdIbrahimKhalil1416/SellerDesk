package com.sellerdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Entry point of the SellerDesk backend.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded because SellerDesk has no
 * username/password login (we use email OTP + JWT). Without the exclusion Spring Boot would
 * create a default in-memory user and print a generated password at startup.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SellerDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(SellerDeskApplication.class, args);
    }
}
