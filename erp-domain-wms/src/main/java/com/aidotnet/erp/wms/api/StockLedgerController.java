package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.PurchasePutawayService;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.StockLedgerFilter;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wms/api/in/v1/inventory-ledger")
public class StockLedgerController {
    private final PurchasePutawayService putaways;
    private final InventoryStore inventory;

    public StockLedgerController(PurchasePutawayService putaways, InventoryStore inventory) {
        this.putaways = putaways;
        this.inventory = inventory;
    }

    @GetMapping("/balances")
    public Result<List<BatchStock>> balances(@RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String locationId, @RequestParam(required = false) String sellerSku,
            @RequestParam(required = false) String batchNo, @RequestParam(required = false) String purchaseOrderId,
            Authentication authentication) {
        return Result.ok(putaways.balances(tenant(authentication),
                new StockLedgerFilter(warehouseId, locationId, sellerSku, batchNo, purchaseOrderId, null)));
    }

    @GetMapping("/transactions")
    public Result<List<InventoryTransaction>> transactions(@RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String locationId, @RequestParam(required = false) String sellerSku,
            @RequestParam(required = false) String batchNo, @RequestParam(required = false) String purchaseOrderId,
            @RequestParam(required = false) String documentId, Authentication authentication) {
        return Result.ok(inventory.listTransactions(tenant(authentication),
                new StockLedgerFilter(warehouseId, locationId, sellerSku, batchNo, purchaseOrderId, documentId)));
    }

    private String tenant(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("库存台账需要使用当前租户的登录账号");
        }
        return user.tenantId();
    }
}
