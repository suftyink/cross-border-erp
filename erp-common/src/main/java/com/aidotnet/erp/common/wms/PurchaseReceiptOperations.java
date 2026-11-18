package com.aidotnet.erp.common.wms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** In-process warehouse contract for confirming an actual purchase delivery. */
public interface PurchaseReceiptOperations {

    Optional<Receipt> findReceipt(String tenantId, String receiptId);

    List<Receipt> listReceipts(String tenantId, String purchaseOrderId);

    List<ReceiptMovement> listReceiptMovements(String tenantId, String receiptId);

    Receipt receive(String tenantId, String purchaseOrderId, String receiptId, String warehouseId,
                    String operatorId, List<DeliveryLine> lines, boolean replacement);

    record DeliveryLine(String purchaseOrderLineId, String sellerSku, String supplierBatchNo,
                        int quantity, BigDecimal unitCost) {}

    record Receipt(String receiptId, String purchaseOrderId, String warehouseId, String operatorId,
                   Instant receivedAt, List<ReceiptLine> lines, boolean replacement) {}

    record ReceiptLine(String purchaseOrderLineId, String sellerSku, String supplierBatchNo,
                       String batchNo, int quantity) {}

    record ReceiptMovement(String receiptId, String purchaseOrderId, String warehouseId,
                           String purchaseOrderLineId, String sellerSku, String batchNo, int quantity,
                           String toStatus, String operatorId, Instant receivedAt) {}
}
