package com.aidotnet.erp.crm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.crm.SalesReturnOrders.Order;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.crm.application.SalesReturnOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/crm/api/in/v1/sales-returns")
public class SalesReturnOrderController {
    private final SalesReturnOrderService returns;
    public SalesReturnOrderController(SalesReturnOrderService returns) { this.returns = returns; }
    @GetMapping
    public Result<List<Order>> list(Authentication auth) { BatchRecallController.requireReader(auth); return Result.ok(returns.list(BatchRecallController.current(auth).tenantId())); }
    @GetMapping("/{id}")
    public Result<SalesReturnOrderService.Detail> get(@PathVariable UUID id, Authentication auth) { BatchRecallController.requireReader(auth); return Result.ok(returns.get(BatchRecallController.current(auth).tenantId(), id.toString())); }
    @PostMapping("/{id}/receipts/{receipt}/verify")
    @RequirePermission("crm:return:write")
    public Result<SalesReturnOrderService.Detail> verify(@PathVariable UUID id, @PathVariable UUID receipt, @Valid @RequestBody Verify request, Authentication auth) {
        var user = BatchRecallController.current(auth); return Result.ok(returns.verify(user.tenantId(), id.toString(), receipt.toString(), request.batchNo().toString(), user.userId()));
    }
    public record Verify(@NotNull UUID batchNo) {}
}
