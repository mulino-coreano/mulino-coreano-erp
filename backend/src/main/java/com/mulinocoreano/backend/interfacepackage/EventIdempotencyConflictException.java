package com.mulinocoreano.backend.interfacepackage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class EventIdempotencyConflictException extends RuntimeException {

    public EventIdempotencyConflictException(String eventType, String externalRef) {
        super("Idempotency key was already used for different event content: "
                + eventType + "/" + externalRef);
    }
}
