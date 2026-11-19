package com.aidotnet.erp.wms.domain;

import java.time.Instant;

public record BatchStock(String batchNo, String receiptId, String purchaseOrderId, String warehouseId,
                         String sellerSku, String supplierBatchNo, Instant receivedAt,
                         String locationId, String locationCode, Status status, int quantity, String sourceType, String sourceDocumentId) {
    public BatchStock(String batchNo, String receiptId, String purchaseOrderId, String warehouseId, String sellerSku,
                      String supplierBatchNo, Instant receivedAt, String locationId, String locationCode, Status status, int quantity) {
        this(batchNo, receiptId, purchaseOrderId, warehouseId, sellerSku, supplierBatchNo, receivedAt, locationId, locationCode, status, quantity, "PURCHASE_RECEIPT", receiptId);
    }
    public enum Status { PENDING_INSPECTION, PENDING_PUTAWAY, QUARANTINED, AVAILABLE, RESERVED, PICKED, PENDING_REVIEW, QUALITY_HOLD }
}
