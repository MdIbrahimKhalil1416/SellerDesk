package com.sellerdesk.common.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app (frontend/dist, copied to {@code classpath:/static/} by the
 * Dockerfile) from the same service as the API.
 *
 * <p>How a GET request is answered:
 * <ol>
 *   <li>A controller matches ({@code /api/v1/...}, {@code /actuator/health}): the controller
 *       answers. Controllers always win over the static-file handlers below.</li>
 *   <li>{@code /assets/...}: Vite's built JS/CSS/images. Their file names contain a content hash
 *       ({@code index-B7x2kQ.js}), so a changed file always gets a new name. That makes it safe to
 *       let browsers cache them for a year without asking again.</li>
 *   <li>Any other existing file ({@code index.html}, {@code favicon.svg}): served with
 *       {@code no-cache}, i.e. the browser must check for a newer version every time. Most
 *       important for index.html: it names the current asset files, so after a deploy users get
 *       the new app on the next page load.</li>
 *   <li>Not a file and not an API path ({@code /dashboard}, {@code /orders/42}): index.html, and
 *       React Router shows the right page. Without this, refreshing the browser on /dashboard
 *       would give 404.</li>
 *   <li>Unknown {@code /api/...} or a missing file ({@code /assets/old.js}, {@code /x.png}): no
 *       fallback, so Spring throws NoResourceFoundException and GlobalExceptionHandler answers
 *       with the usual JSON 404.</li>
 * </ol>
 *
 * <p>In local development the React app is served by the Vite dev server instead, and the
 * backend has no static files: then only the API answers here.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    static final String STATIC_LOCATION = "classpath:/static/";

    /** Paths that belong to the backend. They never fall back to index.html. */
    private static final List<String> BACKEND_PREFIXES = List.of("api/", "actuator/", "v3/api-docs", "swagger-ui");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC_LOCATION + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true);

        // Replaces Spring Boot's default "/**" static handler.
        // resourceChain(false): no cache of "path -> file". With the fallback every random path
        // resolves to index.html, so such a cache could be filled endlessly by bots.
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    /** Looks up the real file first; for SPA routes falls back to index.html. */
    static class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // The parent class also blocks "../" tricks: only files inside static/ are returned.
            Resource file = super.getResource(resourcePath, location);
            if (file != null) {
                return file;
            }
            return isSpaRoute(resourcePath) ? super.getResource("index.html", location) : null;
        }

        /**
         * True for paths React Router handles: not a backend path and the last part has no dot.
         * "orders/42" → true; "api/v1/x", "assets/old.js", "robots.txt" → false.
         */
        static boolean isSpaRoute(String path) {
            if (BACKEND_PREFIXES.stream().anyMatch(path::startsWith)) {
                return false;
            }
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return !lastSegment.contains(".");
        }
    }
}
