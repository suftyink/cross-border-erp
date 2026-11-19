package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.crm.SalesReturnOrders;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.SalesReturnReceiptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/sales-returns")
public class SalesReturnReceiptController {
    private static final String WRITE = "wms:return:write";
    private final SalesReturnReceiptService service;
    public SalesReturnReceiptController(SalesReturnReceiptService service) { this.service = service; }
    @GetMapping("/work")
    public Result<Work> work(Authentication auth) {
        var user = current(auth); boolean allowed = allowed(auth);
        return Result.ok(new Work(allowed, allowed ? service.work(user.tenantId()) : List.of()));
    }
    @GetMapping("/{id}/receipts")
    @RequirePermission(WRITE)
    public Result<List<SalesReturnOrders.Receipt>> list(@PathVariable UUID id, Authentication auth) { return Result.ok(service.list(current(auth).tenantId(), id.toString())); }
    @PostMapping("/{id}/receipts")
    @RequirePermission(WRITE)
    public Result<SalesReturnOrders.Receipt> receive(@PathVariable UUID id, @Valid @RequestBody Receive request, Authentication auth) {
        var user = current(auth); return Result.ok(service.receive(user.tenantId(), id.toString(), request.receiptId().toString(), request.batchNo().toString(), request.quantity(), user.userId()));
    }
    private boolean allowed(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + WRITE) || a.getAuthority().equals("ROLE_SUPER_ADMIN")); }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user) || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Receive(@NotNull UUID receiptId, @NotNull UUID batchNo, @Positive int quantity) {}
    public record Work(boolean canReceive, List<SalesReturnOrders.Order> orders) {}
}
