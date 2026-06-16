package com.app.publishservice.handler;

import com.app.publishservice.api.dto.ApiResponse;
import com.app.publishservice.common.exception.NotFoundException;
import com.app.publishservice.common.exception.StoreApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        log.warn("Request not found, method={}, uri={}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Bad request, method={}, uri={}, message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failure(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ":" + fieldError.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed, method={}, uri={}, message={}", request.getMethod(), request.getRequestURI(), message);
        return ResponseEntity.badRequest().body(ApiResponse.failure(message));
    }

    @ExceptionHandler(StoreApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleStoreApi(StoreApiException ex, HttpServletRequest request) {
        log.warn(
                "Upstream store API failed, method={}, uri={}, status={}, message={}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getStatus().value(),
                ex.getMessage(),
                ex
        );
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.failure(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception, method={}, uri={}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(ex.getMessage()));
    }
}
