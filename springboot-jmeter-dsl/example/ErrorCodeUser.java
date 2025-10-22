// 1) 直接抛业务异常（假设你有 BizException(String code, int http, String msg)）
throw new BizException(ErrorCode.ORD_STATE_ILLEGAL.code(), ErrorCode.ORD_STATE_ILLEGAL.http(),
                       ErrorCode.ORD_STATE_ILLEGAL.formatMessage());

// 2) 在 @ControllerAdvice 中使用 toResponse(...) 快速返回统一响应
return ResponseEntity.status(ErrorCode.COMMON_VALIDATION_FAILED.http())
        .body(ErrorCode.COMMON_VALIDATION_FAILED.toResponse("Invalid fields", Map.of("field", "items[0].quantity")));
