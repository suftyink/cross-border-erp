package com.aidotnet.erp.crm.domain;

import com.aidotnet.erp.common.wms.WarehouseShipments.Trace;
import java.time.Instant;
import java.util.List;

public record BatchRecall(String recallId, String batchNo, String reason, String createdBy, Instant createdAt) {
    public record Line(String lineId, String recallId, String salesOrderId, String outboundId, String warehouseId,
            String sellerSku, String batchNo, String sourceReceiptId, String purchaseOrderId, int expectedQuantity,
            int authorizedQuantity, int receivedQuantity, int confirmedQuantity, int notificationCount, int unidentifiedQuantity,
            String closeReason, String closedBy, Instant closedAt, Integer closedExpectedQuantity, Integer closedReceivedQuantity,
            Integer closedConfirmedQuantity, Integer closedUnidentifiedQuantity) {
        public Line(String lineId, String recallId, String salesOrderId, String outboundId, String warehouseId, String sellerSku, String batchNo,
                    String sourceReceiptId, String purchaseOrderId, int expectedQuantity, int authorizedQuantity, int receivedQuantity, int confirmedQuantity, int notificationCount) {
            this(lineId, recallId, salesOrderId, outboundId, warehouseId, sellerSku, batchNo, sourceReceiptId, purchaseOrderId,
                    expectedQuantity, authorizedQuantity, receivedQuantity, confirmedQuantity, notificationCount, 0, null, null, null, null, null, null, null);
        }
        public int getPendingQuantity() { return expectedQuantity - confirmedQuantity; }
        public String getStatus() {
            if (closedAt != null) return "EXCEPTION_CLOSED";
            return confirmedQuantity == expectedQuantity ? "COMPLETED" : confirmedQuantity > 0 ? "PARTIALLY_RETURNED"
                    : receivedQuantity > 0 ? "RECEIVED_PENDING_VERIFICATION" : notificationCount > 0 ? "NOTIFIED" : "PENDING_NOTIFICATION";
        }
    }
    public record Notification(String notificationId, String lineId, Channel channel, String content, String operatorId, Instant notifiedAt) {}
    public enum Channel { PLATFORM, EMAIL, PHONE, OTHER }
    public record Detail(BatchRecall recall, List<Line> lines, List<Trace> sources, List<Notification> notifications) {}
}
