package com.aidotnet.erp.crm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.WarehouseShipments.Trace;
import com.aidotnet.erp.crm.application.BatchRecallService;
import com.aidotnet.erp.crm.application.SalesReturnOrderService;
import com.aidotnet.erp.crm.domain.BatchRecall;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/crm/api/in/v1/batch-recalls")
public class BatchRecallController {
    private final BatchRecallService recalls;
    private final SalesReturnOrderService returns;
    public BatchRecallController(BatchRecallService recalls, SalesReturnOrderService returns) { this.recalls = recalls; this.returns = returns; }
    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String batchNo, Authentication auth) {
        var user = current(auth); boolean manage = allowed(auth, "crm:recall:write"), ret = allowed(auth, "crm:return:write");
        return Result.ok(new Work(manage, ret, manage || ret ? recalls.list(user.tenantId(), batchNo) : List.of()));
    }
    @GetMapping("/affected")
    @RequirePermission("crm:recall:write")
    public Result<List<Trace>> affected(@RequestParam UUID batchNo, Authentication auth) { return Result.ok(recalls.affected(current(auth).tenantId(), batchNo.toString())); }
    @GetMapping("/{id}")
    public Result<BatchRecall.Detail> get(@PathVariable UUID id, Authentication auth) {
        requireReader(auth); return Result.ok(recalls.get(current(auth).tenantId(), id.toString()));
    }
    @PostMapping
    @RequirePermission("crm:recall:write")
    public Result<BatchRecall.Detail> create(@Valid @RequestBody Create request, Authentication auth) {
        var user = current(auth); return Result.ok(recalls.create(user.tenantId(), request.recallId().toString(), request.batchNo().toString(), request.reason(), user.userId()));
    }
    @PostMapping("/{id}/lines/{line}/notifications")
    @RequirePermission("crm:recall:write")
    public Result<BatchRecall.Detail> notify(@PathVariable UUID id, @PathVariable UUID line, @Valid @RequestBody Notification request, Authentication auth) {
        var user = current(auth); return Result.ok(recalls.notify(user.tenantId(), id.toString(), line.toString(), request.notificationId().toString(), request.channel(), request.content(), user.userId()));
    }
    @PostMapping("/{id}/lines/{line}/returns")
    @RequirePermission("crm:return:write")
    public Result<SalesReturnOrderService.Detail> createReturn(@PathVariable UUID id, @PathVariable UUID line, @Valid @RequestBody Return request, Authentication auth) {
        var user = current(auth); return Result.ok(returns.create(user.tenantId(), id.toString(), line.toString(), request.returnId().toString(), request.quantity(), user.userId()));
    }
    static void requireReader(Authentication auth) {
        current(auth);
        if (!allowed(auth, "crm:recall:write") && !allowed(auth, "crm:return:write") && !allowed(auth, "wms:return:write")) throw new AccessDeniedException("无召回退货查询权限");
    }
    static boolean allowed(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + permission) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    static ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user) || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Work(boolean canRecall, boolean canReturn, List<BatchRecall> recalls) {}
    public record Create(@NotNull UUID recallId, @NotNull UUID batchNo, @NotBlank @Size(max = 500) String reason) {}
    public record Return(@NotNull UUID returnId, @Positive int quantity) {}
    public record Notification(@NotNull UUID notificationId, @NotNull BatchRecall.Channel channel, @NotBlank @Size(max = 1000) String content) {}
}
