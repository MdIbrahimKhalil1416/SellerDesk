package com.sellerdesk.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sellerdesk.common.security.AccessTokenClaims;
import com.sellerdesk.common.security.JwtService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base class for every test that needs a real MongoDB. Extend it; don't repeat the annotations.
 *
 * <ul>
 *   <li>MongoDB comes from {@link MongoTestEnvironment} (MONGODB_TEST_URI, else Testcontainers,
 *       else the class is skipped).</li>
 *   <li>Real indexes (unique, TTL) are created at startup, like production.</li>
 *   <li>Mail is mocked: integration tests never send real email. Tests read codes from the mock.</li>
 *   <li>Before <b>each test</b>, all documents in all collections are deleted. Documents, not
 *       collections: dropping would also drop the indexes created at startup.</li>
 *   <li>Use {@link #uniqueEmail} / {@link #uniqueName}: background tasks (@Async email) from a
 *       previous test may still finish after the cleanup, and must never touch this test's data.</li>
 * </ul>
 *
 * <p>All subclasses share one Spring context (same configuration), so the app starts once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(MongoAvailableCondition.class)
public abstract class MongoIntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MongoTemplate mongoTemplate;

    @MockitoBean
    protected JavaMailSender mailSender;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MongoTestEnvironment::uri);
        // Must be set explicitly: application.yml sets spring.mongodb.database from MONGODB_DATABASE
        // (default "sellerdesk"), which would override the database name in the URI and point the
        // tests at the real database.
        registry.add("spring.mongodb.database", MongoTestEnvironment::database);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @BeforeEach
    void deleteAllDocuments() {
        String database = mongoTemplate.getDb().getName();
        if (!database.endsWith(MongoTestEnvironment.REQUIRED_SUFFIX)) {
            // Last line of defense, checked against the database Spring actually connected to.
            throw new IllegalStateException("Refusing to clean database '" + database + "'");
        }
        for (String collection : mongoTemplate.getCollectionNames()) {
            if (!collection.startsWith("system.")) {
                mongoTemplate.getCollection(collection).deleteMany(new Document());
            }
        }
    }

    // ---- Shared helpers: real shops and owners through the real HTTP flow -----------------

    /** Password used by {@link #createVerifiedOwner}. Meets the password rules. */
    protected static final String OWNER_PASSWORD = "ownerPass2026";

    /**
     * A shop owner created through the real API: register → code from the (mocked) email →
     * verify. Ids are read from the issued access token.
     */
    public record TestOwner(String email, String password, String userId, String shopId, String shopName,
                            String accessToken, Cookie refreshCookie) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    @Autowired
    protected JwtService jwtService;

    protected TestOwner createVerifiedOwner(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        String shopName = uniqueName(prefix + " Shop");
        mockMvc.perform(post("/api/v1/auth/register-shop").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"%s Owner","email":"%s","password":"%s",
                                 "shopName":"%s","shopPhone":"01712345678"}
                                """.formatted(prefix, email, OWNER_PASSWORD, shopName)))
                .andExpect(status().isCreated());

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, awaitEmailedCode(email))))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = JsonPath.read(verified.getResponse().getContentAsString(), "$.accessToken");
        AccessTokenClaims claims = jwtService.parseAccessToken(accessToken);
        return new TestOwner(email, OWNER_PASSWORD, claims.userId(), claims.shopId(), shopName, accessToken,
                verified.getResponse().getCookie("sd_refresh"));
    }

    /**
     * Waits (max 10 s) for the newest email to this address and returns the 6-digit code in it.
     * Emails are sent on a background thread, so they arrive a moment after the request returns.
     * Polls for this exact address, so mails to other test users never confuse it.
     */
    protected String awaitEmailedCode(String email) throws InterruptedException {
        Pattern sixDigits = Pattern.compile("\\b(\\d{6})\\b");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SimpleMailMessage> newest = Mockito.mockingDetails(mailSender).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1
                            && i.getArgument(0) instanceof SimpleMailMessage)
                    .map(i -> (SimpleMailMessage) i.getArgument(0))
                    .filter(m -> m.getTo() != null && email.equals(m.getTo()[0]))
                    .reduce((first, second) -> second);
            if (newest.isPresent()) {
                Matcher matcher = sixDigits.matcher(newest.get().getText());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No email with a code was sent to " + email + " within 10 s");
    }

    /** e.g. {@code flow-3f9a1c2e@example.com}. */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + shortId() + "@example.com";
    }

    /** e.g. {@code Flow Fashion 3f9a1c2e}. */
    protected static String uniqueName(String prefix) {
        return prefix + " " + shortId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
