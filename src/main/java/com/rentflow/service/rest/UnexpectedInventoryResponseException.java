package com.rentflow.service.rest;

import org.springframework.http.HttpStatusCode;

public class UnexpectedInventoryResponseException extends RuntimeException {

    public UnexpectedInventoryResponseException(HttpStatusCode status) {
        super("Unexpected inventory response status: " + status.value());
    }
}
