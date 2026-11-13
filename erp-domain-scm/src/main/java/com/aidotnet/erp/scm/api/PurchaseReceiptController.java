package com.aidotnet.erp.scm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations.Receipt;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations.ReceiptMovement;
import com.aidotnet.erp.scm.application.PurchaseReceivingService;
import com.aidotnet.erp.scm.application.PurchaseReceivingService.ArrivalLine;
import com.aidotnet.erp.scm.application.PurchaseReceivingService.Confirmation;
import com.aidotnet.erp.scm.application.PurchaseService;
import com.aidotnet.erp.scm.domain.PurchaseOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PurchaseReceiptController {

    private static final String RECEIPTS = "/wms/api/in/v1/purchase-receipts";
    private static final String WRITE = "wms:receipt:write";
    private final PurchaseReceivingService receiving;
    private final PurchaseService purchases;
    private final PurchaseReceiptOperations warehouse;

    public PurchaseReceiptController(PurchaseReceivingService receiving, PurchaseService purchases,
                                     PurchaseReceiptOperations warehouse) {
        this.receiving = receiving;
        this.purchases = purchases;
        this.warehouse = warehouse;
    }

    @GetMapping(RECEIPTS + "/work")
    public Result<ReceiptWork> work(Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        boolean canReceive = authentication.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_" + WRITE) || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
        List<PurchaseOrder> pending = canReceive ? purchases.listPurchaseOrders(user.tenantId()).stream()
                .filter(PurchaseOrder::canReceive).toList() : List.of();
        return Result.ok(new ReceiptWork(canReceive, pending));
    }

    @PostMapping(RECEIPTS + "/{purchaseOrderId}")
    @RequirePermission(WRITE)
    public Result<Receipt> receive(@PathVariable String purchaseOrderId, @Valid @RequestBody ReceiptRequest request,
                                   Authentication authentication) {
        return Result.ok(confirm(purchaseOrderId, request, authentication).receipt());
    }

    @PatchMapping("/scm/api/in/v1/purchase-orders/{purchaseOrderId}/receive")
    @RequirePermission(WRITE)
    public Result<PurchaseOrder> receiveFromPurchase(@PathVariable String purchaseOrderId,
                                                      @Valid @RequestBody ReceiptRequest request,
                                                      Authentication authentication) {
        return Result.ok(confirm(purchaseOrderId, request, authentication).purchaseOrder());
    }

    @GetMapping(RECEIPTS)
    public Result<List<Receipt>> list(@RequestParam(required = false) String purchaseOrderId,
                                      Authentication authentication) {
        return Result.ok(warehouse.listReceipts(currentUser(authentication).tenantId(), purchaseOrderId));
    }

    @GetMapping(RECEIPTS + "/{receiptId}/movements")
    public Result<List<ReceiptMovement>> movements(@PathVariable String receiptId, Authentication authentication) {
        return Result.ok(warehouse.listReceiptMovements(currentUser(authentication).tenantId(), receiptId));
    }

    private Confirmation confirm(String orderId, ReceiptRequest request, Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return receiving.confirm(user.tenantId(), user.userId(), orderId, request.receiptId().toString(),
                request.warehouseId(), request.receipts().stream()
                        .map(line -> new ArrivalLine(line.lineId(), line.quantity(), line.supplierBatchNo())).toList());
    }

    private ErpUserDetails currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("收货操作需要使用当前租户的登录账号");
        }
        return user;
    }

    public record ReceiptRequest(@NotNull UUID receiptId, @NotBlank @Size(max = 64) String warehouseId,
                                 @NotEmpty List<@NotNull @Valid ReceiptLineRequest> receipts) {}

    public record ReceiptLineRequest(@NotBlank @Size(max = 64) String lineId, @Positive int quantity,
                                     @Size(max = 128) String supplierBatchNo) {}

    public record ReceiptWork(boolean canReceive, List<PurchaseOrder> purchaseOrders) {}

}
