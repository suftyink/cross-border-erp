package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 调用方先持有拣货作业锁；统一扣减实拣批次、预占和在手，并写出库流水。 */
@Service
public class ShipmentStockPosting {
    private final SalesDispatchMapper stock;
    private final InventoryStore inventory;
    private final BatchQualityGuard quality;
    public ShipmentStockPosting(SalesDispatchMapper stock, InventoryStore inventory, BatchQualityGuard quality) {
        this.stock = stock; this.inventory = inventory; this.quality = quality;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(String tenant, String warehouse, List<Detail> details, Reference reference, String operator, Instant now) {
        Map<String, InventoryBalance> balances = new LinkedHashMap<>();
        for (String sku : details.stream().map(d -> d.line().sellerSku()).distinct().sorted().toList()) {
            balances.put(sku, inventory.lockBalance(tenant, warehouse, sku)
                    .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在")));
        }
        for (Detail detail : details) {
            var line = detail.line();
            InventoryBalance before = balances.get(line.sellerSku());
            quality.requireClear(tenant, line.batchNo());
            int quantity = line.quantity(), physical = stock.physicalQuantity(tenant, line);
            if (quantity <= 0 || before.reserved() < quantity || before.onHand() < quantity || stock.deduct(tenant, line) != 1) {
                String code = reference.type() == InventoryTransaction.TransactionType.SALES_SHIP ? "SALES_SHIPMENT_STOCK_INVALID" : "FBA_SHIPMENT_STOCK_INVALID";
                throw new BizException(code, "实拣暂存或预占库存不足，请核查库存");
            }
            InventoryBalance after = new InventoryBalance(tenant, warehouse, line.sellerSku(), before.onHand() - quantity,
                    before.reserved() - quantity, before.inTransit(), before.frozen(), now);
            inventory.saveBalance(after);
            inventory.saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenant, warehouse, line.sellerSku(),
                    reference.type(), quantity, before.onHand(), before.reserved(), after.onHand(), after.reserved(),
                    reference.typeName(), reference.id(), detail.remark(), now, line.batchNo(), operator,
                    0, 0, 0, line.locationId(), null, physical, physical, null, null, 0));
            balances.put(line.sellerSku(), after);
        }
    }
    public record Detail(WarehouseShipments.Line line, String remark) {}
    public record Reference(InventoryTransaction.TransactionType type, String typeName, String id) {}
}
