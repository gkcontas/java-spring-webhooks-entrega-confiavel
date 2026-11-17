package com.gkcontas.webhooks.error;

import com.gkcontas.webhooks.receiver.InvalidWebhookException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return ResponseEntity.badRequest().body(new ApiError("validation failed", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("malformed request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidWebhookException.class)
    ResponseEntity<ApiError> invalidWebhook(InvalidWebhookException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(e.getMessage()));
    }
}
