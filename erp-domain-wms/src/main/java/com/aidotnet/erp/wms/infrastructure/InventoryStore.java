package com.aidotnet.erp.wms.infrastructure;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryPrediction;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.InventoryTransactionType;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import com.aidotnet.erp.wms.domain.StockCheck;
import com.aidotnet.erp.wms.domain.StockLedgerFilter;
import com.aidotnet.erp.wms.domain.Warehouse;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import com.aidotnet.erp.wms.infrastructure.data.InventoryBalanceDO;
import com.aidotnet.erp.wms.infrastructure.data.InventoryPredictionDO;
import com.aidotnet.erp.wms.infrastructure.data.InventoryTransactionDO;
import com.aidotnet.erp.wms.infrastructure.data.StockCheckDO;
import com.aidotnet.erp.wms.infrastructure.data.StockCheckLineDO;
import com.aidotnet.erp.wms.infrastructure.data.WarehouseDO;
import com.aidotnet.erp.wms.infrastructure.data.WarehouseLocationDO;
import com.aidotnet.erp.wms.infrastructure.mapper.InventoryMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * WMS域库存数据存储
 * <p>
 * 描述: 仓储域核心数据存储层，负责仓库、库位、库存余额、库存事务、盘点、预测等实体的CRUD操作。
 * </p>
 *
 * @author ERP系统
 */
@Repository
public class InventoryStore {

    /** 库存数据MyBatis映射器 */
    private final InventoryMapper inventoryMapper;

    /**
     * 构造函数 - 依赖注入映射器
     *
     * @param inventoryMapper 库存MyBatis映射器
     */
    public InventoryStore(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    public Warehouse saveWarehouse(Warehouse warehouse) {
        WarehouseDO existing = inventoryMapper.selectWarehouse(warehouse.tenantId(), warehouse.warehouseId());
        WarehouseDO data = toWarehouseData(warehouse);
        if (existing == null) {
            inventoryMapper.insertWarehouse(data);
        }
        return warehouse;
    }

    public Optional<Warehouse> findWarehouse(String tenantId, String warehouseId) {
        return Optional.ofNullable(inventoryMapper.selectWarehouse(tenantId, warehouseId))
                .map(this::toWarehouseDomain);
    }

    public void lockReceivingWarehouse(String tenantId, String warehouseId) {
        if (inventoryMapper.lockWarehouse(tenantId, warehouseId) == null) {
            throw new com.aidotnet.erp.common.exception.BizException("WAREHOUSE_NOT_FOUND", "仓库不存在");
        }
    }

    public void receivePendingInspection(String tenantId, String warehouseId, String sku, int quantity,
                                         String receiptId, String batchNo, String operatorId, Instant receivedAt) {
        InventoryBalance before = lockOrCreateBalance(tenantId, warehouseId, sku);
        InventoryBalance after = new InventoryBalance(tenantId, warehouseId, sku,
                Math.addExact(before.onHand(), quantity), before.reserved(), before.inTransit(),
                Math.addExact(before.frozen(), quantity), receivedAt);
        InventoryBalanceDO data = toBalanceData(after);
        data.setUpdatedAt(receivedAt);
        inventoryMapper.updateBalance(data);
        saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenantId, warehouseId, sku,
                InventoryTransaction.TransactionType.RECEIVE, quantity, before.onHand(), before.reserved(),
                after.onHand(), after.reserved(), "PURCHASE_RECEIPT", receiptId, "采购收货转待检", receivedAt,
                batchNo, operatorId, quantity, 0, 0));
    }

    public void recordPurchaseDisposition(String tenantId, PurchaseInspectionBatch batch, String inspectionId,
                                           String operatorId, int good, int defective, Instant at) {
        InventoryBalance balance = lockBalanceWithPurchaseStates(tenantId, batch.warehouseId(), batch.sellerSku())
                .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在"));
        if (balance.legacyFrozenQuantity() < 0 || balance.onHand() < balance.frozen()) {
            throw new BizException("INSPECTION_INVENTORY_MISMATCH", "批次与冻结库存不一致，请核查库存");
        }
        saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenantId, batch.warehouseId(),
                batch.sellerSku(), InventoryTransaction.TransactionType.QUALITY_DISPOSITION, batch.receivedQuantity(),
                balance.onHand(), balance.reserved(), balance.onHand(), balance.reserved(), "PURCHASE_INSPECTION",
                inspectionId, "采购批次整批质检判定", at, batch.batchNo(), operatorId,
                -batch.receivedQuantity(), good, defective));
    }

    public List<Warehouse> listWarehouses(String tenantId) {
        return inventoryMapper.selectWarehouses(tenantId).stream()
                .map(this::toWarehouseDomain).collect(Collectors.toList());
    }

    public InventoryBalance saveBalance(InventoryBalance balance) {
        long located = locatedQuantity(balance.tenantId(), balance.warehouseId(), balance.sellerSku());
        if (located > 0 && balance.getAvailable() < located) {
            throw new BizException("BATCH_STOCK_OPERATION_REQUIRED", "已按库位管理的库存需要按实物批次办理");
        }
        inventoryMapper.ensureBalance(balance.tenantId(), balance.warehouseId(), balance.sellerSku());
        InventoryBalanceDO data = toBalanceData(balance);
        data.setUpdatedAt(Instant.now());
        inventoryMapper.updateBalance(data);
        return balance;
    }

    public long locatedQuantity(String tenantId, String warehouseId, String sellerSku) {
        return inventoryMapper.selectLocatedQuantity(tenantId, warehouseId, sellerSku);
    }

    public boolean hasBatchLocations(String tenantId, String warehouseId, String sellerSku) {
        return inventoryMapper.hasBatchLocations(tenantId, warehouseId, sellerSku);
    }

    public Optional<InventoryBalance> findBalance(String tenantId, String warehouseId, String sellerSku) {
        return Optional.ofNullable(inventoryMapper.selectBalance(tenantId, warehouseId, sellerSku))
                .map(this::toBalanceDomain);
    }

    public Optional<InventoryBalance> lockBalance(String tenantId, String warehouseId, String sellerSku) {
        return Optional.ofNullable(inventoryMapper.lockBalance(tenantId, warehouseId, sellerSku))
                .map(this::toBalanceDomain);
    }

    public Optional<InventoryBalance> lockBalanceWithPurchaseStates(String tenantId, String warehouseId, String sellerSku) {
        return lockBalance(tenantId, warehouseId, sellerSku).flatMap(balance -> findBalance(tenantId, warehouseId, sellerSku));
    }

    public InventoryBalance lockOrCreateBalance(String tenantId, String warehouseId, String sellerSku) {
        inventoryMapper.ensureBalance(tenantId, warehouseId, sellerSku);
        return lockBalance(tenantId, warehouseId, sellerSku).orElseThrow();
    }

    public List<InventoryBalance> listBalances(String tenantId, String warehouseId) {
        return inventoryMapper.selectBalances(tenantId, warehouseId).stream()
                .map(this::toBalanceDomain).collect(Collectors.toList());
    }

    public List<InventoryBalance> listBalancesBySku(String tenantId, String sellerSku) {
        return inventoryMapper.selectBalancesBySku(tenantId, sellerSku).stream()
                .map(this::toBalanceDomain).collect(Collectors.toList());
    }

    public WarehouseLocation saveLocation(WarehouseLocation location) {
        WarehouseLocationDO data = toLocationData(location);
        if (inventoryMapper.insertLocation(data) == 0) {
            throw new BizException("LOCATION_CODE_EXISTS", "仓库内已存在相同库位码");
        }
        return location;
    }

    public List<WarehouseLocation> lockLocationsByCode(String tenantId, String warehouseId, String locationCode) {
        return inventoryMapper.lockLocationsByCode(tenantId, warehouseId, locationCode).stream()
                .map(this::toLocationDomain).toList();
    }

    public boolean updateLocationEnabled(String tenantId, String warehouseId, String locationId, boolean enabled) {
        return inventoryMapper.updateLocationEnabled(tenantId, warehouseId, locationId, enabled) == 1;
    }

    public Optional<WarehouseLocation> findLocation(String tenantId, String locationId) {
        return Optional.ofNullable(inventoryMapper.selectLocation(tenantId, locationId))
                .map(this::toLocationDomain);
    }

    public List<WarehouseLocation> listLocations(String tenantId, String warehouseId) {
        return inventoryMapper.selectLocations(tenantId, warehouseId).stream()
                .map(this::toLocationDomain).collect(Collectors.toList());
    }

    public StockCheck saveStockCheck(StockCheck check) {
        StockCheckDO data = toStockCheckData(check);
        StockCheckDO existing = inventoryMapper.selectStockCheck(check.tenantId(), check.checkId());
        if (existing == null) {
            inventoryMapper.insertStockCheck(data);
        } else {
            inventoryMapper.updateStockCheck(data);
        }
        StockCheckLineDO lineData = toStockCheckLineData(check);
        StockCheckLineDO existingLine = inventoryMapper.selectStockCheckLine(check.tenantId(), check.checkId());
        if (existingLine == null) {
            inventoryMapper.insertStockCheckLine(lineData);
        } else {
            inventoryMapper.updateStockCheckLine(lineData);
        }
        return check;
    }

    public Optional<StockCheck> findStockCheck(String tenantId, String checkId) {
        return Optional.ofNullable(inventoryMapper.selectStockCheck(tenantId, checkId))
                .map(data -> toStockCheckDomain(data, inventoryMapper.selectStockCheckLine(tenantId, checkId)));
    }

    public List<StockCheck> listStockChecks(String tenantId, String warehouseId) {
        return inventoryMapper.selectStockChecks(tenantId, warehouseId).stream()
                .map(data -> toStockCheckDomain(data, inventoryMapper.selectStockCheckLine(tenantId, data.getCheckId())))
                .collect(Collectors.toList());
    }

    public InventoryPrediction savePrediction(InventoryPrediction prediction) {
        InventoryPredictionDO data = toPredictionData(prediction);
        inventoryMapper.insertPrediction(data);
        return prediction;
    }

    public List<InventoryPrediction> listPredictions(String tenantId, String warehouseId) {
        return inventoryMapper.selectPredictions(tenantId, warehouseId).stream()
                .map(this::toPredictionDomain).collect(Collectors.toList());
    }

    public InventoryTransaction saveTransaction(InventoryTransaction transaction) {
        inventoryMapper.insertTransaction(toTransactionData(transaction));
        return transaction;
    }

    public List<InventoryTransaction> listTransactions(String tenantId, String warehouseId, String sellerSku) {
        return listTransactions(tenantId, new StockLedgerFilter(warehouseId, null, sellerSku, null, null, null));
    }

    public List<InventoryTransaction> listTransactions(String tenantId, StockLedgerFilter filter) {
        return inventoryMapper.selectTransactions(tenantId, filter.warehouseId(), filter.sellerSku(),
                        filter.locationId(), filter.batchNo(), filter.purchaseOrderId(), filter.documentId()).stream()
                .map(this::toTransactionDomain)
                .collect(Collectors.toList());
    }

    public Optional<InventoryTransaction> findTransaction(String tenantId, String transactionId) {
        return Optional.ofNullable(inventoryMapper.selectTransaction(tenantId, transactionId))
                .map(this::toTransactionDomain);
    }

    public boolean insertBatchPlacementTransaction(InventoryTransaction transaction) {
        return inventoryMapper.insertTransaction(toTransactionData(transaction)) == 1;
    }

    @org.springframework.transaction.annotation.Transactional
    public void addInventory(String sellerSku, String warehouseId, java.math.BigDecimal quantity,
                             String referenceId, InventoryTransactionType type) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        addInventory(tenantId, sellerSku, warehouseId, quantity, type.name(), referenceId, null, type);
    }

    @org.springframework.transaction.annotation.Transactional
    public void addInventory(String tenantId, String sellerSku, String warehouseId, java.math.BigDecimal quantity,
                             String referenceType, String referenceId, String remark, InventoryTransactionType type) {
        InventoryBalance balance = lockOrCreateBalance(tenantId, warehouseId, sellerSku);
        int beforeOnHand = balance.onHand();
        int afterOnHand = beforeOnHand + quantity.intValue();
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, afterOnHand, balance.reserved(),
                balance.inTransit(), balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, toTransactionType(type), quantity.intValue(), beforeOnHand, balance.reserved(),
                afterOnHand, balance.reserved(), referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deductInventory(String sellerSku, String warehouseId, java.math.BigDecimal quantity,
                                String referenceId, InventoryTransactionType type) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "库存不足: " + sellerSku));
        if (balance.getAvailable() < quantity.intValue()) {
            throw new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "可用库存不足: " + sellerSku);
        }
        int beforeOnHand = balance.onHand();
        int afterOnHand = beforeOnHand - quantity.intValue();
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, afterOnHand, balance.reserved(),
                balance.inTransit(), balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, toTransactionType(type), quantity.intValue(), beforeOnHand, balance.reserved(),
                afterOnHand, balance.reserved(), type.name(), referenceId, null, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deductReservedInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                        String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INVENTORY_NOT_FOUND", "inventory not found: " + sellerSku));
        if (balance.reserved() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "INVENTORY_DEDUCT_EXCEEDS_RESERVED", "reserved inventory is insufficient: " + sellerSku);
        }
        int beforeOnHand = balance.onHand();
        int beforeReserved = balance.reserved();
        int afterOnHand = beforeOnHand - quantity;
        int afterReserved = beforeReserved - quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, afterOnHand, afterReserved,
                balance.inTransit(), balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.DEDUCT, quantity, beforeOnHand, beforeReserved,
                afterOnHand, afterReserved, referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void reserveInventory(String sellerSku, String warehouseId, int quantity, String referenceId) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "库存不存在: " + sellerSku));
        if (balance.getAvailable() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "可用库存不足，无法预占: " + sellerSku);
        }
        int beforeReserved = balance.reserved();
        int afterReserved = beforeReserved + quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), afterReserved,
                balance.inTransit(), balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.RESERVE, quantity, balance.onHand(), beforeReserved,
                balance.onHand(), afterReserved, "RESERVE", referenceId, null, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void releaseInventory(String sellerSku, String warehouseId, int quantity, String referenceId) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "库存不存在: " + sellerSku));
        int beforeReserved = balance.reserved();
        int afterReserved = Math.max(0, beforeReserved - quantity);
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), afterReserved,
                balance.inTransit(), balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.RELEASE, quantity, balance.onHand(), beforeReserved,
                balance.onHand(), afterReserved, "RELEASE", referenceId, null, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void freezeInventory(String sellerSku, String warehouseId, int quantity, String referenceId) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "库存不存在: " + sellerSku));
        int beforeFrozen = balance.frozen();
        int afterFrozen = beforeFrozen + quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                balance.inTransit(), afterFrozen, Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.STOCK_ADJUST, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), "FREEZE", referenceId, "冻结库存", Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void unfreezeInventory(String sellerSku, String warehouseId, int quantity, String referenceId) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockBalanceWithPurchaseStates(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "库存不存在: " + sellerSku));
        int beforeFrozen = balance.frozen();
        int afterFrozen = beforeFrozen - Math.min(quantity, Math.max(0, balance.legacyFrozenQuantity()));
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                balance.inTransit(), afterFrozen, Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.STOCK_ADJUST, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), "UNFREEZE", referenceId, "解冻库存", Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void addInTransit(String sellerSku, String warehouseId, int quantity, String referenceId) {
        String tenantId = com.aidotnet.erp.common.tenant.TenantContext.getTenantId();
        InventoryBalance balance = lockOrCreateBalance(tenantId, warehouseId, sellerSku);
        int beforeInTransit = balance.inTransit();
        int afterInTransit = beforeInTransit + quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                afterInTransit, balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.RECEIVE, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), "IN_TRANSIT_ADD", referenceId, "增加在途库存", Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void addInTransitInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                      String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockOrCreateBalance(tenantId, warehouseId, sellerSku);
        int afterInTransit = balance.inTransit() + quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                afterInTransit, balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.RECEIVE, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void receiveInTransitInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                          String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INVENTORY_NOT_FOUND", "inventory not found: " + sellerSku));
        if (balance.inTransit() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "IN_TRANSIT_INVENTORY_NOT_ENOUGH", "in-transit inventory is insufficient: " + sellerSku);
        }
        int afterOnHand = balance.onHand() + quantity;
        int afterInTransit = balance.inTransit() - quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, afterOnHand, balance.reserved(),
                afterInTransit, balance.frozen(), Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.RECEIVE, quantity, balance.onHand(), balance.reserved(),
                afterOnHand, balance.reserved(), referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void freezeInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockBalance(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "inventory not found: " + sellerSku));
        if (balance.getAvailable() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "INSUFFICIENT_AVAILABLE_INVENTORY", "可用库存不足，无法冻结: " + sellerSku);
        }
        int beforeFrozen = balance.frozen();
        int afterFrozen = beforeFrozen + quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                balance.inTransit(), afterFrozen, Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.STOCK_ADJUST, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void unfreezeInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                  String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockBalanceWithPurchaseStates(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INSUFFICIENT_INVENTORY", "inventory not found: " + sellerSku));
        if (balance.legacyFrozenQuantity() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "INSUFFICIENT_FROZEN_INVENTORY", "冻结库存不足，无法释放: " + sellerSku);
        }
        int beforeFrozen = balance.frozen();
        int afterFrozen = beforeFrozen - quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, balance.onHand(), balance.reserved(),
                balance.inTransit(), afterFrozen, Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.STOCK_ADJUST, quantity, balance.onHand(), balance.reserved(),
                balance.onHand(), balance.reserved(), referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void consumeFrozenInventory(String tenantId, String sellerSku, String warehouseId, int quantity,
                                       String referenceType, String referenceId, String remark) {
        InventoryBalance balance = lockBalanceWithPurchaseStates(tenantId, warehouseId, sellerSku)
                .orElseThrow(() -> new com.aidotnet.erp.common.exception.BizException("INVENTORY_NOT_FOUND", "inventory not found: " + sellerSku));
        if (balance.legacyFrozenQuantity() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "INSUFFICIENT_FROZEN_INVENTORY", "frozen inventory is insufficient: " + sellerSku);
        }
        if (balance.onHand() < quantity) {
            throw new com.aidotnet.erp.common.exception.BizException(
                    "INSUFFICIENT_ON_HAND_INVENTORY", "on-hand inventory is insufficient: " + sellerSku);
        }
        int beforeOnHand = balance.onHand();
        int beforeReserved = balance.reserved();
        int afterOnHand = beforeOnHand - quantity;
        int afterFrozen = balance.frozen() - quantity;
        saveBalance(new InventoryBalance(tenantId, warehouseId, sellerSku, afterOnHand, beforeReserved,
                balance.inTransit(), afterFrozen, Instant.now()));
        InventoryTransaction txn = new InventoryTransaction(java.util.UUID.randomUUID().toString(), tenantId, warehouseId,
                sellerSku, InventoryTransaction.TransactionType.DEDUCT, quantity, beforeOnHand, beforeReserved,
                afterOnHand, beforeReserved, referenceType, referenceId, remark, Instant.now());
        saveTransaction(txn);
    }

    private InventoryTransaction.TransactionType toTransactionType(InventoryTransactionType type) {
        return switch (type) {
            case RECEIVE, TRANSFER_IN, STOCK_CHECK_GAIN -> InventoryTransaction.TransactionType.RECEIVE;
            case DEDUCT, TRANSFER_OUT, STOCK_CHECK_LOSS -> InventoryTransaction.TransactionType.DEDUCT;
            case RESERVE -> InventoryTransaction.TransactionType.RESERVE;
            case RELEASE -> InventoryTransaction.TransactionType.RELEASE;
            case STOCK_ADJUST -> InventoryTransaction.TransactionType.STOCK_ADJUST;
        };
    }

    private WarehouseDO toWarehouseData(Warehouse w) {
        WarehouseDO data = new WarehouseDO();
        data.setWarehouseId(w.warehouseId());
        data.setTenantId(w.tenantId());
        data.setCode(w.code());
        data.setName(w.name());
        data.setType(w.type());
        data.setCountryCode(w.countryCode());
        data.setAddress(w.address());
        data.setStatus(w.status());
        data.setContactPerson(w.contactPerson());
        data.setPhone(w.phone());
        data.setCreatedAt(w.createdAt() != null ? w.createdAt() : Instant.now());
        data.setUpdatedAt(w.updatedAt() != null ? w.updatedAt() : Instant.now());
        return data;
    }

    private Warehouse toWarehouseDomain(WarehouseDO d) {
        return new Warehouse(d.getWarehouseId(), d.getTenantId(), d.getCode(), d.getName(), d.getType(),
                d.getCountryCode(), d.getAddress(), d.getStatus(), d.getContactPerson(), d.getPhone(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private InventoryBalanceDO toBalanceData(InventoryBalance b) {
        InventoryBalanceDO data = new InventoryBalanceDO();
        data.setTenantId(b.tenantId());
        data.setWarehouseId(b.warehouseId());
        data.setSellerSku(b.sellerSku());
        data.setOnHand(b.onHand());
        data.setReserved(b.reserved());
        data.setInTransit(b.inTransit());
        data.setFrozen(b.frozen());
        return data;
    }

    private InventoryBalance toBalanceDomain(InventoryBalanceDO d) {
        return new InventoryBalance(d.getTenantId(), d.getWarehouseId(), d.getSellerSku(),
                d.getOnHand() != null ? d.getOnHand() : 0,
                d.getReserved() != null ? d.getReserved() : 0,
                d.getInTransit() != null ? d.getInTransit() : 0,
                d.getFrozen() != null ? d.getFrozen() : 0,
                d.getUpdatedAt(), d.getPendingInspection(), d.getPendingPutaway(), d.getQuarantined());
    }

    private WarehouseLocationDO toLocationData(WarehouseLocation l) {
        WarehouseLocationDO data = new WarehouseLocationDO();
        data.setLocationId(l.locationId());
        data.setTenantId(l.tenantId());
        data.setWarehouseId(l.warehouseId());
        data.setZoneCode(l.zone());
        data.setAisle(l.aisle());
        data.setShelf(l.shelf());
        data.setBin(l.bin());
        data.setLocationCode(l.locationCode());
        data.setPurpose(l.purpose());
        data.setEnabled(l.enabled());
        data.setCreatedAt(Instant.now());
        data.setUpdatedAt(Instant.now());
        return data;
    }

    private WarehouseLocation toLocationDomain(WarehouseLocationDO d) {
        return new WarehouseLocation(d.getLocationId(), d.getTenantId(), d.getWarehouseId(),
                d.getLocationCode(), d.getZoneCode(), d.getAisle(), d.getShelf(), d.getBin(),
                d.getEnabled() != null ? d.getEnabled() : true,
                d.getCreatedAt(), d.getUpdatedAt(), d.getPurpose());
    }

    private StockCheckDO toStockCheckData(StockCheck c) {
        StockCheckDO data = new StockCheckDO();
        data.setCheckId(c.checkId());
        data.setTenantId(c.tenantId());
        data.setWarehouseId(c.warehouseId());
        data.setCheckType("FULL");
        data.setStatus(c.status().name());
        data.setScheduledAt(c.checkedAt());
        data.setCompletedAt(c.status() == StockCheck.CheckStatus.COMPLETED || c.status() == StockCheck.CheckStatus.ADJUSTED ? c.checkedAt() : null);
        data.setCreatedAt(c.createdAt());
        data.setUpdatedAt(Instant.now());
        return data;
    }

    private StockCheckLineDO toStockCheckLineData(StockCheck check) {
        StockCheckLineDO data = new StockCheckLineDO();
        data.setCheckId(check.checkId());
        data.setTenantId(check.tenantId());
        data.setSellerSku(check.sellerSku());
        data.setLocationId(null);
        data.setSystemQty(check.systemQuantity());
        data.setActualQty(check.actualQuantity());
        data.setDiffQty(check.difference());
        data.setStatus(check.status().name());
        data.setCreatedAt(check.createdAt());
        return data;
    }

    private StockCheck toStockCheckDomain(StockCheckDO d, StockCheckLineDO line) {
        return new StockCheck(
                d.getCheckId(),
                d.getTenantId(),
                d.getWarehouseId(),
                line != null ? line.getSellerSku() : null,
                line != null && line.getSystemQty() != null ? line.getSystemQty() : 0,
                line != null && line.getActualQty() != null ? line.getActualQty() : 0,
                line != null && line.getDiffQty() != null ? line.getDiffQty() : 0,
                StockCheck.CheckStatus.valueOf(d.getStatus()),
                null,
                d.getCompletedAt() != null ? d.getCompletedAt() : d.getScheduledAt(),
                d.getCreatedAt());
    }

    private InventoryPredictionDO toPredictionData(InventoryPrediction p) {
        InventoryPredictionDO data = new InventoryPredictionDO();
        data.setPredictionId(p.predictionId());
        data.setTenantId(p.tenantId());
        data.setWarehouseId(p.warehouseId());
        data.setSellerSku(p.sellerSku());
        data.setPredictedDemand(p.predictedDemand30d());
        data.setPredictedDaysOfSupply(java.math.BigDecimal.valueOf(p.daysOfStock()));
        data.setRecommendedRestockQty(p.predictedDemand30d() - p.currentStock());
        data.setPredictionDate(p.predictedAt() != null ? p.predictedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate() : java.time.LocalDate.now());
        data.setHorizonDays(30);
        data.setCreatedAt(p.predictedAt());
        return data;
    }

    private InventoryPrediction toPredictionDomain(InventoryPredictionDO d) {
        return new InventoryPrediction(d.getPredictionId(), d.getTenantId(), d.getWarehouseId(),
                d.getSellerSku(), 0,
                d.getPredictedDemand() != null ? d.getPredictedDemand() / 4 : 0,
                d.getPredictedDemand() != null ? d.getPredictedDemand() : 0,
                d.getPredictedDaysOfSupply() != null ? d.getPredictedDaysOfSupply().intValue() : 0,
                InventoryPrediction.PredictionLevel.SUFFICIENT,
                d.getCreatedAt());
    }

    private InventoryTransactionDO toTransactionData(InventoryTransaction transaction) {
        InventoryTransactionDO data = new InventoryTransactionDO();
        data.setTransactionId(transaction.transactionId());
        data.setTenantId(transaction.tenantId());
        data.setWarehouseId(transaction.warehouseId());
        data.setSellerSku(transaction.sellerSku());
        data.setTransactionType(transaction.transactionType().name());
        data.setQuantity(transaction.quantity());
        data.setBeforeOnHand(transaction.beforeOnHand());
        data.setBeforeReserved(transaction.beforeReserved());
        data.setAfterOnHand(transaction.afterOnHand());
        data.setAfterReserved(transaction.afterReserved());
        data.setReferenceType(transaction.referenceType());
        data.setReferenceId(transaction.referenceId());
        data.setRemark(transaction.remark());
        data.setCreatedAt(transaction.createdAt());
        data.setBatchNo(transaction.batchNo());
        data.setOperatorId(transaction.operatorId());
        data.setPendingInspectionDelta(transaction.pendingInspectionDelta());
        data.setPendingPutawayDelta(transaction.pendingPutawayDelta());
        data.setQuarantinedDelta(transaction.quarantinedDelta());
        data.setSourceLocationId(transaction.sourceLocationId());
        data.setTargetLocationId(transaction.targetLocationId());
        data.setBeforeLocationQuantity(transaction.beforeLocationQuantity());
        data.setAfterLocationQuantity(transaction.afterLocationQuantity());
        data.setBeforePendingPutaway(transaction.beforePendingPutaway());
        data.setAfterPendingPutaway(transaction.afterPendingPutaway());
        data.setAvailableDelta(transaction.availableDelta());
        return data;
    }

    private InventoryTransaction toTransactionDomain(InventoryTransactionDO data) {
        return new InventoryTransaction(
                data.getTransactionId(),
                data.getTenantId(),
                data.getWarehouseId(),
                data.getSellerSku(),
                InventoryTransaction.TransactionType.valueOf(data.getTransactionType()),
                data.getQuantity() != null ? data.getQuantity() : 0,
                data.getBeforeOnHand() != null ? data.getBeforeOnHand() : 0,
                data.getBeforeReserved() != null ? data.getBeforeReserved() : 0,
                data.getAfterOnHand() != null ? data.getAfterOnHand() : 0,
                data.getAfterReserved() != null ? data.getAfterReserved() : 0,
                data.getReferenceType(),
                data.getReferenceId(),
                data.getRemark(),
                data.getCreatedAt(), data.getBatchNo(), data.getOperatorId(), data.getPendingInspectionDelta(),
                data.getPendingPutawayDelta(), data.getQuarantinedDelta(),
                data.getSourceLocationId(), data.getTargetLocationId(), data.getBeforeLocationQuantity(),
                data.getAfterLocationQuantity(), data.getBeforePendingPutaway(), data.getAfterPendingPutaway(),
                data.getAvailableDelta());
    }
}
