package com.aidotnet.erp.wms.domain;

import java.time.Instant;

public record BatchStock(String batchNo, String receiptId, String purchaseOrderId, String warehouseId,
                         String sellerSku, String supplierBatchNo, Instant receivedAt,
                         String locationId, String locationCode, Status status, int quantity) {
    public enum Status { PENDING_INSPECTION, PENDING_PUTAWAY, QUARANTINED, AVAILABLE, RESERVED, PICKED, PENDING_REVIEW }
}
