@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @GetMapping
    public ApiResponse<List<OrderDto>> list(@RequestParam int page, @RequestParam int size) {
        // 假设查询结果
        List<OrderDto> items = List.of(new OrderDto("ORD-001", "PAID", 12999));
        long total = 153L;
        return ApiResponse.ok(items, ApiResponse.pageMeta(page, size, total, page * size < total))
                          .withRequestId(UUID.randomUUID().toString());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreatedVo>> create(@RequestBody CreateOrderCmd cmd) {
        // 参数校验举例（真实项目建议用 @Valid + @ControllerAdvice 统一处理）
        if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
            var err = new ApiResponse.ErrorDetail("VALIDATION_FAILED", "items", "items cannot be empty");
            return ResponseEntity.unprocessableEntity()
                    .body(ApiResponse.validationFailed(List.of(err))
                                     .withRequestId(UUID.randomUUID().toString()));
        }
        // 正常创建
        OrderCreatedVo vo = new OrderCreatedVo("ORD-20251023-00001234", "PENDING_PAYMENT", 12999);
        return ResponseEntity.ok(
                ApiResponse.ok(vo)
                           .withRequestId(UUID.randomUUID().toString())
        );
    }
}
