package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.PurchaseSupplierReturns.SupplierReturn;
import com.aidotnet.erp.wms.application.PurchaseSupplierReturnService;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wms/api/in/v1/purchase-returns")
public class PurchaseSupplierReturnController {
    private static final String ISOLATE = "wms:quarantine:write";
    private static final String REQUEST = "scm:purchase-return:write";
    private static final String SHIP = "wms:purchase-return:ship";
    private final PurchaseSupplierReturnService returns;

    public PurchaseSupplierReturnController(PurchaseSupplierReturnService returns) {
        this.returns = returns;
    }

    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String purchaseOrderId, Authentication authentication) {
        String tenantId = user(authentication).tenantId();
        boolean canIsolate = permitted(authentication, ISOLATE);
        boolean canRequest = permitted(authentication, REQUEST);
        boolean canShip = permitted(authentication, SHIP);
        return Result.ok(new Work(canIsolate, canRequest, canShip,
                canIsolate ? returns.pendingIsolation(tenantId, purchaseOrderId) : List.of(),
                canIsolate || canRequest || canShip ? returns.listByPurchaseOrder(tenantId, purchaseOrderId) : List.of()));
    }

    @PostMapping("/batches/{batch}/isolate")
    @RequirePermission(ISOLATE)
    public Result<InventoryTransaction> isolate(@PathVariable UUID batch, @Valid @RequestBody Placement request,
                                                Authentication authentication) {
        ErpUserDetails user = user(authentication);
        return Result.ok(returns.isolate(user.tenantId(), batch.toString(), user.userId(),
                new PurchaseSupplierReturnService.Placement(request.requestId().toString(),
                        request.locationCode(), request.quantity())));
    }

    @PostMapping("/receipts/{receipt}")
    @RequirePermission(REQUEST)
    public Result<SupplierReturn> request(@PathVariable UUID receipt, @Valid @RequestBody Request request,
                                          Authentication authentication) {
        ErpUserDetails user = user(authentication);
        return Result.ok(returns.request(user.tenantId(), receipt.toString(), user.userId(),
                new PurchaseSupplierReturnService.Request(request.returnId().toString(), request.batchNo().toString(),
                        request.locationId(), request.quantity(), request.reason().strip(), request.replace())));
    }

    @PostMapping("/{id}/ship")
    @RequirePermission(SHIP)
    public Result<SupplierReturn> ship(@PathVariable UUID id, @Valid @RequestBody Shipment request,
                                       Authentication authentication) {
        ErpUserDetails user = user(authentication);
        return Result.ok(returns.ship(user.tenantId(), id.toString(), user.userId(), request.quantity()));
    }

    private static boolean permitted(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_" + permission) || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }

    private static ErpUserDetails user(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("请使用当前租户的登录账号办理采购退供");
        }
        return user;
    }

    public record Work(boolean canIsolate, boolean canRequest, boolean canShip, List<BatchStock> pendingIsolation,
                        List<SupplierReturn> returns) {}
    public record Placement(@NotNull UUID requestId, @NotBlank @Size(max = 128) String locationCode,
                             @Positive int quantity) {}
    public record Request(@NotNull UUID returnId, @NotNull UUID batchNo, @NotBlank @Size(max = 64) String locationId,
                           @Positive int quantity, @NotBlank @Size(max = 500) String reason, boolean replace) {}
    public record Shipment(@Positive int quantity) {}
}
