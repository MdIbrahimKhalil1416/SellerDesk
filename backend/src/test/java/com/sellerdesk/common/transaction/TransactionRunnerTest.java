package com.sellerdesk.common.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoException;
import com.sellerdesk.common.error.BusinessRuleException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Retry rules of {@link TransactionRunner}, with a fake transaction manager (no database). */
class TransactionRunnerTest {

    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionRunner runner = new TransactionRunner(new TransactionTemplate(manager), 4, Duration.ofSeconds(10));

    @BeforeEach
    void fakeTransactions() {
        when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
    }

    /** What Spring throws for a MongoDB write conflict inside a transaction. */
    private static RuntimeException writeConflict() {
        MongoException cause = new MongoException(112, "WriteConflict");
        cause.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return new UncategorizedMongoDbException("Write conflict", cause);
    }

    @Test
    void returnsTheResultAndCommitsOnce() {
        assertThat(runner.run(() -> "done")).isEqualTo("done");
        verify(manager, times(1)).commit(any());
    }

    @Test
    void retriesATransientErrorAndThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = runner.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw writeConflict();
            }
            return "third time";
        });
        assertThat(result).isEqualTo("third time");
        assertThat(calls).hasValue(3);
        verify(manager, times(2)).rollback(any());
    }

    @Test
    void unknownCommitResultIsAlsoRetried() {
        AtomicInteger calls = new AtomicInteger();
        runner.run(() -> {
            if (calls.incrementAndGet() == 1) {
                MongoException e = new MongoException("commit result unknown");
                e.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                throw new UncategorizedMongoDbException("unknown", e);
            }
            return 1;
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> runner.run(() -> {
            calls.incrementAndGet();
            throw writeConflict();
        })).isInstanceOf(UncategorizedMongoDbException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void givesUpWhenTheTimeBudgetIsUsed() {
        TransactionRunner quick = new TransactionRunner(new TransactionTemplate(manager), 1000, Duration.ZERO);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> quick.run(() -> {
            calls.incrementAndGet();
            throw writeConflict();
        })).isInstanceOf(UncategorizedMongoDbException.class);
        assertThat(calls.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void businessErrorsAreNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> runner.run(() -> {
            calls.incrementAndGet();
            throw new BusinessRuleException("INSUFFICIENT_STOCK", "no");
        })).isInstanceOf(BusinessRuleException.class);
        assertThat(calls).hasValue(1);
        verify(manager, times(1)).rollback(any());
    }

    @Test
    void mongoErrorWithoutTransientLabelIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> runner.run(() -> {
            calls.incrementAndGet();
            throw new UncategorizedMongoDbException("boom", new MongoException(2, "BadValue"));
        })).isInstanceOf(UncategorizedMongoDbException.class);
        assertThat(calls).hasValue(1);
    }
}
