package com.aidotnet.erp.common.wms;

import java.time.Instant;
import java.util.List;

public interface PurchaseSupplierReturns {
    List<SupplierReturn> listByPurchaseOrder(String tenantId, String purchaseOrderId);

    record SupplierReturn(String returnId, String receiptId, String purchaseOrderId, String purchaseOrderLineId,
                          String batchNo, String warehouseId, String locationId, String locationCode, String sellerSku,
                          int requestedQuantity, String reason, String requestedBy, Instant requestedAt,
                          Integer shippedQuantity, String shippedBy, Instant shippedAt) {}
}
