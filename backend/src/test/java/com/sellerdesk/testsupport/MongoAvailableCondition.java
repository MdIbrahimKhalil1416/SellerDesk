package com.sellerdesk.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit runs this before a test class starts (before Spring). No MongoDB → the class is skipped
 * and the reason appears in the test report. Unsafe {@code MONGODB_TEST_URI} → the exception
 * makes the class fail loudly instead of silently skipping.
 */
public class MongoAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        MongoTestEnvironment.Decision decision = MongoTestEnvironment.current();
        return decision.source() == MongoTestEnvironment.Source.NONE
                ? ConditionEvaluationResult.disabled(decision.reason())
                : ConditionEvaluationResult.enabled(decision.reason());
    }
}
