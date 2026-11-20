package com.sellerdesk.common.storage;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code sellerdesk.cloudinary}.
 *
 * @param url      {@code CLOUDINARY_URL}, format {@code cloudinary://<api_key>:<api_secret>@<cloud_name>}.
 *                 A secret: only from the environment, never written in a file in git.
 *                 Empty is allowed in dev (uploads then fail with 502), not in prod.
 * @param required {@code true} in prod: the app refuses to start without a URL
 */
@Validated
@ConfigurationProperties(prefix = "sellerdesk.cloudinary")
public record CloudinaryProperties(
        @Pattern(regexp = "|cloudinary://\\S+",
                message = "must look like cloudinary://<api_key>:<api_secret>@<cloud_name> (set CLOUDINARY_URL)")
        String url,
        boolean required) {
}
