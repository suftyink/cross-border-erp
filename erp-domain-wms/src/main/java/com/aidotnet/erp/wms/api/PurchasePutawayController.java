package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.PurchasePutawayService;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
@RequestMapping("/wms/api/in/v1/purchase-putaways")
public class PurchasePutawayController {
    private static final String WRITE = "wms:putaway:write";
    private final PurchasePutawayService putaways;

    public PurchasePutawayController(PurchasePutawayService putaways) {
        this.putaways = putaways;
    }

    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String purchaseOrderId, Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        boolean allowed = authentication.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_" + WRITE) || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
        List<Pending> batches = allowed ? putaways.work(user.tenantId(), purchaseOrderId).stream().map(Pending::from).toList()
                : List.of();
        return Result.ok(new Work(allowed, batches));
    }

    @GetMapping("/{batchNo}/recommendation")
    @RequirePermission(WRITE)
    public Result<Recommendation> recommend(@PathVariable UUID batchNo, Authentication authentication) {
        return Result.ok(new Recommendation(putaways.recommend(currentUser(authentication).tenantId(), batchNo.toString())));
    }

    @PostMapping("/{batchNo}")
    @RequirePermission(WRITE)
    public Result<InventoryTransaction> confirm(@PathVariable UUID batchNo, @Valid @RequestBody Request request,
                                                 Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(putaways.confirm(user.tenantId(), batchNo.toString(), user.userId(),
                new PurchasePutawayService.Command(request.requestId().toString(), request.locationCode(), request.quantity())));
    }

    private ErpUserDetails currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("上架操作需要使用当前租户的登录账号");
        }
        return user;
    }

    public record Request(@NotNull UUID requestId, @NotBlank @Size(max = 128) String locationCode,
                          @Positive int quantity) {}
    public record Work(boolean canPutaway, List<Pending> batches) {}
    public record Recommendation(String locationCode) {}
    public record Pending(String batchNo, String receiptId, String purchaseOrderId, String warehouseId,
                          String sellerSku, String supplierBatchNo, Instant receivedAt, int pendingPutaway) {
        static Pending from(BatchStock batch) {
            return new Pending(batch.batchNo(), batch.receiptId(), batch.purchaseOrderId(), batch.warehouseId(),
                    batch.sellerSku(), batch.supplierBatchNo(), batch.receivedAt(), batch.quantity());
        }
    }
}
