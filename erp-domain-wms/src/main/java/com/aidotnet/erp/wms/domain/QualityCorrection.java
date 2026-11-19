package com.aidotnet.erp.wms.domain;

import java.time.Instant;

/** 一次更正只重检仍在库的受影响良品，原判定快照和出库历史保留。 */
public record QualityCorrection(String correctionId, String batchNo, String receiptId, String purchaseOrderId,
        String warehouseId, String sellerSku, int quantity, String reason, String originalInspectionId,
        String originalInspection, String createdBy, Instant createdAt, String releasedBy, Instant releasedAt,
        int reversedQuantity, int directPendingQuantity, int excludedMissingQuantity, String withdrawnBy, Instant withdrawnAt,
        Integer goodQuantity, Integer defectiveQuantity, String inspectedBy, Instant inspectedAt) {
    public String getStatus() {
        return inspectedAt != null ? "COMPLETED" : withdrawnAt != null ? "PENDING_INSPECTION" : "PAUSED";
    }
}
