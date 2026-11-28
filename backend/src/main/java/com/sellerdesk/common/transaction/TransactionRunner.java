package com.sellerdesk.common.transaction;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a block of code in a MongoDB transaction and <b>runs it again</b> when MongoDB says the
 * failure was temporary (docs/SCHEMA.md §11, docs/modules/INVENTORY.md §2).
 *
 * <h2>Why retry at all?</h2>
 * MongoDB transactions do not wait for locks. When two transactions write the same document at
 * the same time (two orders reserving stock of the same product), the second one fails at once
 * with a {@code WriteConflict}, labelled {@code TransientTransactionError}. Running it again a
 * moment later usually works, now against the new data. Spring's {@code @Transactional} never
 * retries by itself, so this class does it.
 *
 * <h2>Why is a full retry safe?</h2>
 * After {@code UnknownTransactionCommitResult} the first attempt may in fact have committed. A
 * retry then runs the work a second time, so <b>only idempotent work may run here</b>: restock and
 * adjustment check their {@code requestId} first, order changes check the order status first.
 *
 * <p>Retries happen after a short random pause (so competing requests spread out), for at most
 * {@link #MAX_RETRY_TIME} (and {@link #MAX_ATTEMPTS} attempts), and never for other errors: an
 * {@code INSUFFICIENT_STOCK} is a real answer, not a temporary failure.
 */
@Slf4j
@Component
public class TransactionRunner {

    /** Safety cap on attempts in total, including the first one. */
    public static final int MAX_ATTEMPTS = 50;

    /**
     * No new attempt is started after this time. Conflicting transactions on one document must
     * commit one after the other, and against a remote cluster one transaction takes a few hundred
     * milliseconds, so the budget is time-based (like the driver's own {@code withTransaction},
     * which retries for up to 120 s).
     */
    public static final Duration MAX_RETRY_TIME = Duration.ofSeconds(10);

    private static final Duration MAX_PAUSE = Duration.ofMillis(100);

    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;
    private final Duration maxRetryTime;

    @Autowired
    public TransactionRunner(TransactionTemplate transactionTemplate) {
        this(transactionTemplate, MAX_ATTEMPTS, MAX_RETRY_TIME);
    }

    /** For tests: other limits. */
    TransactionRunner(TransactionTemplate transactionTemplate, int maxAttempts, Duration maxRetryTime) {
        this.transactionTemplate = transactionTemplate;
        this.maxAttempts = maxAttempts;
        this.maxRetryTime = maxRetryTime;
    }

    /** Runs {@code work} in a transaction; commits if it returns, rolls back if it throws. */
    public <T> T run(Supplier<T> work) {
        long deadline = System.nanoTime() + maxRetryTime.toNanos();
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isTransient(e) || attempt >= maxAttempts || System.nanoTime() > deadline) {
                    throw e;
                }
                log.debug("Transient transaction error, attempt {}: {}", attempt, e.getMessage());
                pause(attempt);
            }
        }
    }

    /**
     * True if MongoDB marked the error as temporary. Spring wraps driver exceptions in its own
     * types, so the whole cause chain is searched.
     */
    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException mongo
                    && (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Random pause between 0 and 10 ms × 2^attempt (20, 40, 80 ms...), capped at 100 ms. Random
     * ("full jitter") so that requests that collided once do not collide again at the same moment.
     */
    private static void pause(int attempt) {
        long ceiling = Math.min(MAX_PAUSE.toMillis(), 10L << Math.min(attempt, 4));
        long millis = ThreadLocalRandom.current().nextLong(1, ceiling + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a transaction", e);
        }
    }
}
