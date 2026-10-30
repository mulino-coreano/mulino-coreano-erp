package com.mulinocoreano.backend.interfacepackage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidInterfaceRequestException extends IllegalArgumentException {

    public InvalidInterfaceRequestException(String message) {
        super(message);
    }

    public InvalidInterfaceRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
