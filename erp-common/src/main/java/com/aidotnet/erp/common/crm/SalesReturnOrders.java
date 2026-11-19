package com.aidotnet.erp.common.crm;

import java.time.Instant;
import java.util.List;

/** CRM 授权销售退货数量；WMS 在同进程事务内回写实际收货，不发起退款。 */
public interface SalesReturnOrders {
    Order getOrder(String tenantId, String returnId);
    Order lock(String tenantId, String returnId);
    List<Order> list(String tenantId);
    void recordReceipt(String tenantId, Receipt receipt);

    record Order(String returnId, String recallId, String recallLineId, String salesOrderId, String outboundId,
            String warehouseId, String sellerSku, String expectedBatchNo, String sourceReceiptId, String purchaseOrderId,
            int quantity, int receivedQuantity, int confirmedQuantity, String createdBy, Instant createdAt) {
        public int getPendingReceiptQuantity() { return quantity - receivedQuantity; }
        public String getStatus() {
            return confirmedQuantity == quantity ? "COMPLETED" : receivedQuantity == quantity ? "RECEIVED_PENDING_VERIFICATION"
                    : receivedQuantity > 0 ? "PARTIALLY_RECEIVED" : "AWAITING_RECEIPT";
        }
    }
    record Receipt(String receiptId, String returnId, String batchNo, int quantity, String operatorId, Instant receivedAt,
                   String verifiedBy, Instant verifiedAt) {}
}
