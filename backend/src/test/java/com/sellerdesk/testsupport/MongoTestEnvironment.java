package com.sellerdesk.testsupport;

import com.mongodb.ConnectionString;
import java.util.function.BooleanSupplier;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Decides, once per test run, where integration tests get MongoDB from:
 * <ol>
 *   <li>{@code MONGODB_TEST_URI} set (env var or backend/.env) → that database, e.g. a separate
 *       Atlas database. Its name <b>must end with {@code -test}</b>, otherwise the run is refused:
 *       tests delete all data before each test, so pointing them at the real database would wipe it.</li>
 *   <li>else Docker available → a throwaway Testcontainers MongoDB (single-node replica set, so
 *       transactions work). Started once and shared by all test classes.</li>
 *   <li>else → integration tests are skipped with a message saying how to enable them.</li>
 * </ol>
 */
public final class MongoTestEnvironment {

    public static final String TEST_URI_VARIABLE = "MONGODB_TEST_URI";
    public static final String REQUIRED_SUFFIX = "-test";
    static final String CONTAINER_DATABASE = "sellerdesk-test";
    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:8.0");

    public enum Source { CONFIGURED_URI, TESTCONTAINERS, NONE }

    /**
     * @param uri      connection string to use (null when skipped)
     * @param database database name to use (always ends with -test; null when skipped)
     * @param reason   human-readable explanation, shown in test reports
     */
    public record Decision(Source source, String uri, String database, String reason) {
    }

    private static Decision decision;
    private static MongoDBContainer container;

    private MongoTestEnvironment() {
    }

    /** The decision for this JVM, made on first use. */
    public static synchronized Decision current() {
        if (decision == null) {
            decision = decide(TestEnv.get(TEST_URI_VARIABLE).orElse(null),
                    () -> DockerClientFactory.instance().isDockerAvailable());
        }
        return decision;
    }

    /**
     * The pure decision logic, separate so it can be unit-tested without Docker or Atlas.
     *
     * @throws IllegalStateException if a test URI is configured but unsafe
     */
    static Decision decide(String testUri, BooleanSupplier dockerAvailable) {
        if (testUri != null && !testUri.isBlank()) {
            String database = requireTestDatabase(testUri);
            return new Decision(Source.CONFIGURED_URI, testUri, database,
                    "Using MongoDB from " + TEST_URI_VARIABLE + " (database '" + database + "')");
        }
        if (dockerAvailable.getAsBoolean()) {
            return new Decision(Source.TESTCONTAINERS, null, CONTAINER_DATABASE,
                    "Using Testcontainers MongoDB (" + MONGO_IMAGE + ")");
        }
        return new Decision(Source.NONE, null, null,
                "Integration tests skipped: no MongoDB available. Set " + TEST_URI_VARIABLE
                        + " in backend/.env (a database whose name ends with '" + REQUIRED_SUFFIX
                        + "') or start Docker.");
    }

    /** Returns the database name from the URI, or refuses if it does not end with -test. */
    static String requireTestDatabase(String uri) {
        String database;
        try {
            database = new ConnectionString(uri).getDatabase();
        } catch (IllegalArgumentException e) {
            // Never put the URI itself in the message: it contains the password.
            throw new IllegalStateException(TEST_URI_VARIABLE + " is not a valid MongoDB connection string");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalStateException(TEST_URI_VARIABLE + " has no database name. Add one ending with '"
                    + REQUIRED_SUFFIX + "', e.g. mongodb+srv://user:pass@host/sellerdesk-test");
        }
        if (!database.endsWith(REQUIRED_SUFFIX)) {
            throw new IllegalStateException("Refusing to run integration tests against database '" + database
                    + "': its name must end with '" + REQUIRED_SUFFIX + "'. Tests delete all data in it.");
        }
        return database;
    }

    /** Connection string for Spring. Starts the container on first call when Testcontainers is used. */
    public static synchronized String uri() {
        Decision d = current();
        return switch (d.source()) {
            case CONFIGURED_URI -> d.uri();
            case TESTCONTAINERS -> startedContainer().getReplicaSetUrl(CONTAINER_DATABASE);
            case NONE -> throw new IllegalStateException(d.reason());
        };
    }

    public static String database() {
        return current().database();
    }

    private static MongoDBContainer startedContainer() {
        if (container == null) {
            container = new MongoDBContainer(MONGO_IMAGE);
            // No stop(): Testcontainers' cleanup container (Ryuk) removes it when the JVM exits,
            // so one container serves every test class.
            container.start();
        }
        return container;
    }
}
