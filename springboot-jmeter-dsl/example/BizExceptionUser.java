// 在业务代码中抛出：

if (!order.canPay()) {
    throw new BizException(ErrorCode.ORD_STATE_ILLEGAL, "Order is not payable in current state",
            Map.of("orderNo", order.getOrderNo(), "state", order.getStatus()),
            List.of(new ApiResponse.ErrorDetail("STATE_ILLEGAL", "status",
                    "Order state not payable", "PENDING_PAYMENT", order.getStatus())));
}


// 在全局异常处理（@RestControllerAdvice）中统一转换：
@ExceptionHandler(BizException.class)
public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest req) {
    ApiResponse<Void> body = ex.toApiResponse().withRequestId((String) req.getAttribute("REQUEST_ID"));
    // 选择是否使用 ex.getHttp() 作为 HTTP 返回码；有的团队统一 200，这里示例用实际 http 码：
    return ResponseEntity.status(ex.getHttp()).body(body);
}

