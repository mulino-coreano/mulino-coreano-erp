package com.mulinocoreano.backend.interfacepackage;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Row locks serialize target changes. READ COMMITTED keeps blocker reads fresh after waiting for a
 * Work Item lock (including concurrently inserted Attention and waiting conditions).
 * Deadlock/serialization retries always restart the entire atomic answer.
 */
@Component
public class AttentionAnswerTransactions {
    private final TransactionTemplate transaction;

    public AttentionAnswerTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (RuntimeException error) {
                if (!retryable(error)) throw error;
                if (attempt == 2)
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "ATTENTION_CONCURRENT_CHANGE");
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private boolean retryable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState())))
                return true;
        }
        return false;
    }
}
