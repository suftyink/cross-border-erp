package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.PurchaseInspection;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import com.aidotnet.erp.wms.domain.StockLedgerFilter;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchaseInspectionMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchasePutawayMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchasePutawayService {
    private static final String REFERENCE_TYPE = "PURCHASE_PUTAWAY";
    private final PurchasePutawayMapper putaways;
    private final PurchaseInspectionMapper inspections;
    private final InventoryStore inventory;

    public PurchasePutawayService(PurchasePutawayMapper putaways, PurchaseInspectionMapper inspections,
                                  InventoryStore inventory) {
        this.putaways = putaways;
        this.inspections = inspections;
        this.inventory = inventory;
    }

    public List<BatchStock> work(String tenantId, String purchaseOrderId) {
        return putaways.balances(tenantId, new StockLedgerFilter(null, null, null, null, purchaseOrderId, null), true);
    }

    public List<BatchStock> balances(String tenantId, StockLedgerFilter filter) {
        return putaways.balances(tenantId, filter, false);
    }

    public String recommend(String tenantId, String batchNo) {
        PurchaseInspectionBatch batch = batch(tenantId, batchNo, false);
        return putaways.recommendLocation(tenantId, batch.warehouseId(), batch.sellerSku());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InventoryTransaction confirm(String tenantId, String batchNo, String operatorId, Command command) {
        PurchaseInspectionBatch batch = batch(tenantId, batchNo, true);
        String locationCode = command.locationCode().strip();
        String transactionId = UUID.nameUUIDFromBytes((REFERENCE_TYPE + "|" + tenantId + "|" + command.requestId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        var existing = inventory.findTransaction(tenantId, transactionId);
        if (existing.isPresent()) {
            InventoryTransaction transaction = existing.get();
            if (transaction.transactionType() == InventoryTransaction.TransactionType.PURCHASE_PUTAWAY
                    && batchNo.equals(transaction.batchNo()) && command.quantity() == transaction.quantity()
                    && inventory.findLocation(tenantId, transaction.targetLocationId())
                        .filter(location -> locationCode.equals(location.locationCode())).isPresent()) {
                return transaction;
            }
            throw conflict();
        }

        PurchaseInspection inspection = inspections.selectInspection(tenantId, batchNo);
        if (inspection == null || inspection.disposedAt() == null) {
            throw new BizException("PUTAWAY_INSPECTION_REQUIRED", "采购批次尚未完成整批质检");
        }
        int pending = inspection.goodQuantity() - inspection.putawayQuantity();
        if (command.quantity() <= 0 || command.quantity() > pending) {
            throw new BizException("PUTAWAY_QUANTITY_INVALID", "上架数量必须大于零且不能超过该批次待上架数量");
        }
        WarehouseLocation location = validLocation(tenantId, batch.warehouseId(), locationCode);
        InventoryBalance balance = inventory.lockBalanceWithPurchaseStates(tenantId, batch.warehouseId(), batch.sellerSku())
                .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在"));
        if (balance.pendingPutaway() < command.quantity() || balance.legacyFrozenQuantity() < 0
                || (long) balance.frozen() + balance.reserved() > balance.onHand()) {
            throw new BizException("PUTAWAY_INVENTORY_MISMATCH", "批次与冻结库存不一致，请核查库存");
        }
        Integer stored = putaways.locationQuantity(tenantId, batchNo, location.locationId());
        int before = stored == null ? 0 : stored;
        int quantity = command.quantity();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        InventoryTransaction transaction = new InventoryTransaction(transactionId, tenantId, batch.warehouseId(),
                batch.sellerSku(), InventoryTransaction.TransactionType.PURCHASE_PUTAWAY, quantity,
                balance.onHand(), balance.reserved(), balance.onHand(), balance.reserved(), REFERENCE_TYPE,
                command.requestId(), locationCode, now, batchNo, operatorId, 0, -quantity, 0,
                null, location.locationId(), before, before + quantity, pending, pending - quantity, quantity);
        if (!inventory.insertPutawayTransaction(transaction)) {
            throw conflict();
        }
        putaways.addToLocation(tenantId, batch, location.locationId(), quantity);
        putaways.recordPutaway(tenantId, batchNo, quantity);
        inventory.saveBalance(new InventoryBalance(tenantId, batch.warehouseId(), batch.sellerSku(),
                balance.onHand(), balance.reserved(), balance.inTransit(), balance.frozen() - quantity, now));
        return transaction;
    }

    private WarehouseLocation validLocation(String tenantId, String warehouseId, String code) {
        List<WarehouseLocation> matches = inventory.lockLocationsByCode(tenantId, warehouseId, code);
        if (matches.size() != 1 || !matches.get(0).enabled() || matches.get(0).purpose() != WarehouseLocation.Purpose.GOOD) {
            throw new BizException("PUTAWAY_LOCATION_INVALID", "请使用当前仓库中唯一、已启用的良品库位码");
        }
        return matches.get(0);
    }

    private PurchaseInspectionBatch batch(String tenantId, String batchNo, boolean lock) {
        PurchaseInspectionBatch batch = inspections.selectBatch(tenantId, batchNo, lock);
        if (batch == null) {
            throw new BizException("PUTAWAY_BATCH_NOT_FOUND", "采购收货批次不存在");
        }
        return batch;
    }

    private BizException conflict() {
        return new BizException("PUTAWAY_REQUEST_CONFLICT", "该上架编号已用于另一笔确认");
    }

    public record Command(String requestId, String locationCode, int quantity) {}
}
