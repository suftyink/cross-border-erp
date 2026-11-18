package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.scm.PurchaseReplacementOperations;
import com.aidotnet.erp.common.wms.PurchaseSupplierReturns;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.PurchaseInspection;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchaseInspectionMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchasePutawayMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchaseSupplierReturnMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseSupplierReturnService implements PurchaseSupplierReturns {
    private final PurchaseSupplierReturnMapper returns;
    private final PurchaseInspectionMapper inspections;
    private final PurchasePutawayMapper locations;
    private final InventoryStore inventory;
    private final PurchaseReplacementOperations replacements;

    public PurchaseSupplierReturnService(PurchaseSupplierReturnMapper returns, PurchaseInspectionMapper inspections,
                                         PurchasePutawayMapper locations, InventoryStore inventory,
                                         PurchaseReplacementOperations replacements) {
        this.returns = returns;
        this.inspections = inspections;
        this.locations = locations;
        this.inventory = inventory;
        this.replacements = replacements;
    }

    public List<BatchStock> pendingIsolation(String tenantId, String purchaseOrderId) {
        return returns.pendingIsolation(tenantId, purchaseOrderId);
    }

    @Override
    public List<SupplierReturn> listByPurchaseOrder(String tenantId, String purchaseOrderId) {
        return returns.listReturns(tenantId, purchaseOrderId);
    }

    @Transactional
    public InventoryTransaction isolate(String tenantId, String batchNo, String operatorId, Placement command) {
        PurchaseInspectionBatch batch = lockBatch(tenantId, batchNo);
        String code = command.locationCode().strip();
        int quantity = command.quantity();
        var type = InventoryTransaction.TransactionType.PURCHASE_QUARANTINE;
        String transactionId = transactionId(type, tenantId, command.requestId());
        var existing = inventory.findTransaction(tenantId, transactionId);
        if (existing.isPresent()) {
            InventoryTransaction movement = existing.get();
            if (batchNo.equals(movement.batchNo()) && quantity == movement.quantity()
                    && inventory.findLocation(tenantId, movement.targetLocationId())
                        .filter(location -> code.equals(location.locationCode())).isPresent()) {
                return movement;
            }
            throw new BizException("QUARANTINE_REQUEST_CONFLICT", "该隔离确认编号已用于另一笔操作");
        }
        PurchaseInspection inspection = inspections.selectInspection(tenantId, batchNo);
        if (inspection == null || inspection.disposedAt() == null
                || quantity > inspection.defectiveQuantity() - inspection.isolatedQuantity()) {
            throw new BizException("QUARANTINE_QUANTITY_INVALID", "隔离数量超过该批次尚未入位的次品数量");
        }
        List<WarehouseLocation> matches = inventory.lockLocationsByCode(tenantId, batch.warehouseId(), code);
        if (matches.size() != 1 || !matches.get(0).enabled() || matches.get(0).purpose() != WarehouseLocation.Purpose.QUARANTINE) {
            throw new BizException("QUARANTINE_LOCATION_INVALID", "请输入当前仓库已启用的隔离库位码");
        }
        String locationId = matches.get(0).locationId();
        InventoryBalance balance = lockInventory(tenantId, batch);
        int before = atLocation(tenantId, batchNo, locationId);
        InventoryTransaction movement = movement(transactionId, command.requestId(), batch, balance, type, operatorId,
                quantity, 0, null, locationId, code, before, before + quantity, now());
        if (!inventory.insertBatchPlacementTransaction(movement)) {
            throw new BizException("QUARANTINE_REQUEST_CONFLICT", "该隔离确认编号已用于另一笔操作");
        }
        locations.addToLocation(tenantId, batch, locationId, quantity);
        returns.recordIsolation(tenantId, batchNo, quantity);
        return movement;
    }

    @Transactional
    public SupplierReturn request(String tenantId, String receiptId, String operatorId, Request command) {
        PurchaseInspectionBatch batch = lockBatch(tenantId, command.batchNo());
        if (!receiptId.equals(batch.receiptId())) {
            throw new BizException("RETURN_SOURCE_MISMATCH", "次品批次不属于该收货记录");
        }
        SupplierReturn existing = returns.findReturn(tenantId, command.returnId(), false);
        if (existing != null) {
            if (existing.batchNo().equals(batch.batchNo()) && existing.locationId().equals(command.locationId())
                    && existing.requestedQuantity() == command.quantity() && existing.reason().equals(command.reason())
                    && existing.replace() == command.replace()) {
                return existing;
            }
            throw returnConflict();
        }
        WarehouseLocation location = inventory.findLocation(tenantId, command.locationId())
                .filter(item -> batch.warehouseId().equals(item.warehouseId())
                        && item.purpose() == WarehouseLocation.Purpose.QUARANTINE && item.locationCode() != null)
                .orElseThrow(() -> new BizException("RETURN_LOCATION_INVALID", "请选择原批次对应的隔离库位"));
        long available = atLocation(tenantId, batch.batchNo(), location.locationId())
                - returns.pendingReturnQuantity(tenantId, batch.batchNo(), location.locationId());
        if (command.quantity() > available) {
            throw new BizException("RETURN_QUANTITY_INVALID", "申请数量超过该批次在隔离库位的可退数量");
        }
        SupplierReturn document = new SupplierReturn(command.returnId(), receiptId, batch.purchaseOrderId(),
                batch.purchaseOrderLineId(), batch.batchNo(), batch.warehouseId(), location.locationId(),
                location.locationCode(), batch.sellerSku(), command.quantity(), command.reason(), operatorId,
                now(), null, null, null, command.replace());
        if (returns.insertReturn(tenantId, document) != 1) {
            throw returnConflict();
        }
        return document;
    }

    @Transactional
    public SupplierReturn ship(String tenantId, String returnId, String operatorId, int quantity) {
        SupplierReturn source = findReturn(tenantId, returnId, false);
        if (source.replace()) {
            replacements.lockOrder(tenantId, source.purchaseOrderId());
        }
        PurchaseInspectionBatch batch = lockBatch(tenantId, source.batchNo());
        SupplierReturn document = findReturn(tenantId, returnId, true);
        if (document.shippedAt() != null) {
            if (document.shippedQuantity() == quantity) {
                return document;
            }
            throw new BizException("RETURN_ALREADY_SHIPPED", "此退供单已确认实发数量");
        }
        int before = atLocation(tenantId, batch.batchNo(), document.locationId());
        if (quantity > document.requestedQuantity() || quantity > before) {
            throw new BizException("RETURN_QUANTITY_INVALID", "实发数量不能超过申请量或原批次隔离库位余量");
        }
        InventoryBalance balance = lockInventory(tenantId, batch);
        if (balance.quarantined() < quantity || returns.deductLocation(tenantId, batch.batchNo(), document.locationId(), quantity) != 1) {
            throw new BizException("RETURN_QUANTITY_INVALID", "原批次在隔离库位的余量不足");
        }
        Instant shippedAt = now();
        var type = InventoryTransaction.TransactionType.PURCHASE_SUPPLIER_RETURN;
        inventory.saveTransaction(movement(transactionId(type, tenantId, returnId), returnId, batch, balance,
                type, operatorId, quantity, quantity, document.locationId(), null, document.locationCode(),
                before, before - quantity, shippedAt));
        returns.recordShippedQuantity(tenantId, batch.batchNo(), quantity);
        returns.shipReturn(tenantId, returnId, quantity, operatorId, shippedAt);
        if (document.replace()) {
            replacements.recordReturnedForReplacement(tenantId, document.purchaseOrderId(),
                    document.purchaseOrderLineId(), quantity);
        }
        inventory.saveBalance(new InventoryBalance(tenantId, batch.warehouseId(), batch.sellerSku(),
                balance.onHand() - quantity, balance.reserved(), balance.inTransit(), balance.frozen() - quantity, shippedAt));
        return findReturn(tenantId, returnId, false);
    }

    private InventoryTransaction movement(String id, String documentId, PurchaseInspectionBatch batch, InventoryBalance balance,
                                          InventoryTransaction.TransactionType type, String operatorId, int quantity,
                                          int outbound, String from, String to, String code, int before, int after, Instant at) {
        return new InventoryTransaction(id, balance.tenantId(), batch.warehouseId(), batch.sellerSku(), type, quantity,
                balance.onHand(), balance.reserved(), balance.onHand() - outbound, balance.reserved(), type.name(),
                documentId, code, at, batch.batchNo(), operatorId, 0, 0, -outbound,
                from, to, before, after, null, null, 0);
    }

    private InventoryBalance lockInventory(String tenantId, PurchaseInspectionBatch batch) {
        InventoryBalance balance = inventory.lockBalanceWithPurchaseStates(tenantId, batch.warehouseId(), batch.sellerSku())
                .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在"));
        if (balance.legacyFrozenQuantity() < 0 || (long) balance.frozen() + balance.reserved() > balance.onHand()) {
            throw new BizException("QUARANTINE_INVENTORY_MISMATCH", "批次与冻结库存不一致，请核查库存");
        }
        return balance;
    }

    private PurchaseInspectionBatch lockBatch(String tenantId, String batchNo) {
        PurchaseInspectionBatch batch = inspections.selectBatch(tenantId, batchNo, true);
        if (batch == null) {
            throw new BizException("QUARANTINE_BATCH_NOT_FOUND", "采购收货批次不存在");
        }
        return batch;
    }

    private SupplierReturn findReturn(String tenantId, String returnId, boolean lock) {
        SupplierReturn document = returns.findReturn(tenantId, returnId, lock);
        if (document == null) {
            throw new BizException("PURCHASE_RETURN_NOT_FOUND", "采购退供单不存在");
        }
        return document;
    }

    private int atLocation(String tenantId, String batchNo, String locationId) {
        Integer quantity = locations.locationQuantity(tenantId, batchNo, locationId);
        return quantity == null ? 0 : quantity;
    }

    private static String transactionId(InventoryTransaction.TransactionType type, String tenantId, String requestId) {
        return UUID.nameUUIDFromBytes((type.name() + "|" + tenantId + "|" + requestId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static BizException returnConflict() {
        return new BizException("PURCHASE_RETURN_CONFLICT", "该采购退供单编号已被使用");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public record Placement(String requestId, String locationCode, int quantity) {}
    public record Request(String returnId, String batchNo, String locationId, int quantity, String reason, boolean replace) {}
}
