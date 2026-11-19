package com.sellerdesk.testsupport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads settings for tests the same way the app does: a real environment variable wins, otherwise
 * the value from {@code backend/.env} (Java properties format, see .env.example).
 *
 * <p>Needed because the decision "which MongoDB?" is made <b>before</b> Spring starts, so
 * Spring's own {@code .env} import is not available yet.
 */
public final class TestEnv {

    /** Same two locations as {@code spring.config.import} in application.yml. */
    private static final List<Path> ENV_FILES = List.of(Path.of(".env"), Path.of("backend", ".env"));

    private static final Properties DOT_ENV = loadDotEnv();

    private TestEnv() {
    }

    public static Optional<String> get(String key) {
        String fromEnvironment = System.getenv(key);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Optional.of(fromEnvironment.trim());
        }
        String fromFile = DOT_ENV.getProperty(key);
        return fromFile == null || fromFile.isBlank() ? Optional.empty() : Optional.of(fromFile.trim());
    }

    private static Properties loadDotEnv() {
        Properties properties = new Properties();
        for (Path file : ENV_FILES) {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + file.toAbsolutePath(), e);
                }
                break;
            }
        }
        return properties;
    }
}
