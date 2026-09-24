package com.mulinocoreano.backend.procurement;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.function.Supplier;

/** Every serialization retry starts after rollback with a new database snapshot. */
@Component
public class PurchaseTransactions {
    private final TransactionTemplate transaction;

    public PurchaseTransactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
                            HttpStatus.CONFLICT, "PURCHASE_CONCURRENT_CHANGE");
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
