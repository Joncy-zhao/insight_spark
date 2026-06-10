package com.insightspark.common;

import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException ex) {
        return json(ApiResponse.badRequest(ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Object>> dataAccess(DataAccessException ex) {
        return json(ApiResponse.error("\u6570\u636e\u5e93\u64cd\u4f5c\u5931\u8d25\uff1a" + ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> serverError(Exception ex) {
        return json(ApiResponse.error(ex.getMessage()));
    }

    private ResponseEntity<ApiResponse<Object>> json(ApiResponse<Object> response) {
        return ResponseEntity
                .status(response.getCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
