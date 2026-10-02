package com.mulinocoreano.backend.interfacepackage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ActiveRunConflictException extends RuntimeException {

    public ActiveRunConflictException(String workItemRef) {
        super("An active RUNNING Run already exists for workItemRef: " + workItemRef);
    }
}
