package com.aidotnet.erp.wms.domain;

import java.time.Instant;

public record ShortPickCase(String shortPickId, String outboundId, String allocationId, String warehouseId,
        String sellerSku, String locationId, String locationCode, String batchNo, String receiptId, String purchaseOrderId,
        int shortQuantity, boolean allowBatchSubstitution, String reason, String reportedBy, Instant reportedAt,
        String replacementRequestId, String replacementAllocationId, String replacementBatchNo, String replacedBy, Instant replacedAt,
        Integer foundQuantity, Integer missingQuantity, String countedBy, Instant countedAt, String countRemark,
        String confirmedBy, Instant confirmedAt) {
    public int getPendingReview() { return confirmedAt == null ? shortQuantity : 0; }
    public String getStatus() { return confirmedAt != null ? "CONFIRMED" : countedAt != null ? "AWAITING_APPROVAL" : "PENDING_COUNT"; }
}
