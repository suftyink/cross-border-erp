package com.aidotnet.erp.wms.domain;

import java.time.Instant;

public record PurchaseInspectionBatch(
        String batchNo, String receiptId, String purchaseOrderId, String purchaseOrderLineId,
        String warehouseId, String sellerSku, String supplierBatchNo, int receivedQuantity,
        Instant receivedAt, Status status
) {
    public enum Status { PENDING_SAMPLE, PENDING_RECHECK, PENDING_DISPOSITION, DISPOSED }

}
