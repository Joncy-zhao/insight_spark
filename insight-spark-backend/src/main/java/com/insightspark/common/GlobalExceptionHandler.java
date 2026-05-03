package com.insightspark.common;

import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Object> badRequest(IllegalArgumentException ex) {
        return ApiResponse.badRequest(ex.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ApiResponse<Object> dataAccess(DataAccessException ex) {
        return ApiResponse.error("数据库操作失败：" + ex.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Object> serverError(Exception ex) {
        return ApiResponse.error(ex.getMessage());
    }
}
