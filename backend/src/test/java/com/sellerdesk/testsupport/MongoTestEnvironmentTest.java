package com.sellerdesk.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.testsupport.MongoTestEnvironment.Decision;
import com.sellerdesk.testsupport.MongoTestEnvironment.Source;
import org.junit.jupiter.api.Test;

/** The "which MongoDB?" rules, without needing Docker or Atlas. */
class MongoTestEnvironmentTest {

    @Test
    void configuredTestUriWinsEvenWhenDockerIsAvailable() {
        Decision d = MongoTestEnvironment.decide("mongodb+srv://u:p@cluster.example.net/sellerdesk-test?retryWrites=true", () -> true);

        assertThat(d.source()).isEqualTo(Source.CONFIGURED_URI);
        assertThat(d.database()).isEqualTo("sellerdesk-test");
    }

    @Test
    void dockerIsUsedWhenNoUriIsConfigured() {
        Decision d = MongoTestEnvironment.decide(null, () -> true);

        assertThat(d.source()).isEqualTo(Source.TESTCONTAINERS);
        assertThat(d.database()).endsWith("-test");
    }

    @Test
    void skippedWithHelpfulReasonWhenNothingIsAvailable() {
        Decision d = MongoTestEnvironment.decide("  ", () -> false);

        assertThat(d.source()).isEqualTo(Source.NONE);
        assertThat(d.reason()).contains("MONGODB_TEST_URI").contains("Docker");
    }

    @Test
    void realDatabaseNameIsRefused() {
        assertThatThrownBy(() -> MongoTestEnvironment.decide("mongodb+srv://u:secret@cluster.example.net/sellerdesk", () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must end with '-test'")
                .message().doesNotContain("secret");
    }

    @Test
    void namesThatOnlyContainTestAreRefused() {
        assertThatThrownBy(() -> MongoTestEnvironment.decide("mongodb://localhost/test-sellerdesk", () -> true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MongoTestEnvironment.decide("mongodb://localhost/sellerdesk-testing", () -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void uriWithoutDatabaseIsRefused() {
        assertThatThrownBy(() -> MongoTestEnvironment.decide("mongodb+srv://u:p@cluster.example.net/?retryWrites=true", () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no database name");
    }

    @Test
    void invalidUriIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> MongoTestEnvironment.decide("not a uri with password hunter2", () -> true))
                .isInstanceOf(IllegalStateException.class)
                .message().doesNotContain("hunter2");
    }
}
