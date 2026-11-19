package com.aidotnet.erp.wms.domain;

import java.time.Instant;

/**
 * 库存事务领域模型
 * <p>
 * 描述: 库存变动事务记录，记录每次库存变动前后的数量快照和关联单据。
 * </p>
 *
 * @author ERP系统
 */
public record InventoryTransaction(
        String transactionId,
        String tenantId,
        String warehouseId,
        String sellerSku,
        TransactionType transactionType,
        int quantity,
        int beforeOnHand,
        int beforeReserved,
        int afterOnHand,
        int afterReserved,
        String referenceType,
        String referenceId,
        String remark,
        Instant createdAt,
        String batchNo,
        String operatorId,
        Integer pendingInspectionDelta,
        Integer pendingPutawayDelta,
        Integer quarantinedDelta,
        String sourceLocationId,
        String targetLocationId,
        Integer beforeLocationQuantity,
        Integer afterLocationQuantity,
        Integer beforePendingPutaway,
        Integer afterPendingPutaway,
        Integer availableDelta,
        Integer pendingReviewDelta
) {
    public InventoryTransaction(String transactionId, String tenantId, String warehouseId, String sellerSku,
                                TransactionType transactionType, int quantity, int beforeOnHand, int beforeReserved,
                                int afterOnHand, int afterReserved, String referenceType, String referenceId,
                                String remark, Instant createdAt, String batchNo, String operatorId,
                                Integer pendingInspectionDelta, Integer pendingPutawayDelta, Integer quarantinedDelta,
                                String sourceLocationId, String targetLocationId, Integer beforeLocationQuantity,
                                Integer afterLocationQuantity, Integer beforePendingPutaway, Integer afterPendingPutaway,
                                Integer availableDelta) {
        this(transactionId, tenantId, warehouseId, sellerSku, transactionType, quantity, beforeOnHand, beforeReserved,
                afterOnHand, afterReserved, referenceType, referenceId, remark, createdAt, batchNo, operatorId,
                pendingInspectionDelta, pendingPutawayDelta, quarantinedDelta, sourceLocationId, targetLocationId,
                beforeLocationQuantity, afterLocationQuantity, beforePendingPutaway, afterPendingPutaway, availableDelta, null);
    }
    public InventoryTransaction(String transactionId, String tenantId, String warehouseId, String sellerSku,
                                TransactionType transactionType, int quantity, int beforeOnHand, int beforeReserved,
                                int afterOnHand, int afterReserved, String referenceType, String referenceId,
                                String remark, Instant createdAt) {
        this(transactionId, tenantId, warehouseId, sellerSku, transactionType, quantity, beforeOnHand,
                beforeReserved, afterOnHand, afterReserved, referenceType, referenceId, remark, createdAt,
                null, null, null, null, null);
    }

    public InventoryTransaction(String transactionId, String tenantId, String warehouseId, String sellerSku,
                                TransactionType transactionType, int quantity, int beforeOnHand, int beforeReserved,
                                int afterOnHand, int afterReserved, String referenceType, String referenceId,
                                String remark, Instant createdAt, String batchNo, String operatorId,
                                Integer pendingInspectionDelta, Integer pendingPutawayDelta, Integer quarantinedDelta) {
        this(transactionId, tenantId, warehouseId, sellerSku, transactionType, quantity, beforeOnHand, beforeReserved,
                afterOnHand, afterReserved, referenceType, referenceId, remark, createdAt, batchNo, operatorId,
                pendingInspectionDelta, pendingPutawayDelta, quarantinedDelta, null, null, null, null, null, null, null);
    }
    /** 事务类型 */
    public enum TransactionType {
        /** 入库 */
        RECEIVE,
        /** 预占 */
        RESERVE,
        /** 释放预占 */
        RELEASE,
        /** 扣减(出库) */
        DEDUCT,
        /** 库存调整 */
        STOCK_ADJUST,
        QUALITY_DISPOSITION,
        PURCHASE_PUTAWAY,
        PURCHASE_QUARANTINE,
        PURCHASE_SUPPLIER_RETURN,
        SALES_PICK,
        SALES_SHIP,
        SHORT_PICK_REPORT,
        SHORT_PICK_REPLACE,
        STOCK_REVIEW,
        QUALITY_HOLD,
        QUALITY_RELEASE,
        PUTAWAY_REVERSE,
        SALES_UNPICK,
        INSPECTION_WITHDRAW,
        QUALITY_REINSPECT
    }
}
