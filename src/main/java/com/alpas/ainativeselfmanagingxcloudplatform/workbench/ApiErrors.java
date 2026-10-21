package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code, String message, List<String> details) {}

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<Error> platform(PlatformException failure) {
        return ResponseEntity.status(failure.status())
                .body(new Error(failure.code(), failure.getMessage(), failure.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Error> malformed() {
        return ResponseEntity.badRequest()
                .body(
                        new Error(
                                "INVALID_JSON",
                                "JSON must match the complete request schema, without unknown"
                                    + " fields or null primitive values.",
                                List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> validation(MethodArgumentNotValidException failure) {
        return ResponseEntity.badRequest()
                .body(
                        new Error(
                                "INVALID_REQUEST",
                                "Check the request fields.",
                                failure.getBindingResult().getFieldErrors().stream()
                                        .map(e -> e.getField() + ": " + e.getDefaultMessage())
                                        .toList()));
    }
}
