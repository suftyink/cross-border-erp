package com.aidotnet.erp.wms.domain;

import java.math.BigDecimal;

public record InboundOrderLine(String lineId, String orderId, String sellerSku, String locationId,
                               int expectedQuantity, int receivedQuantity, BigDecimal unitCost,
                               String batchNo, String purchaseOrderLineId, String supplierBatchNo, Integer receiptPosition) {
    public InboundOrderLine(String lineId, String orderId, String sellerSku, String locationId,
                            int expectedQuantity, int receivedQuantity, BigDecimal unitCost, String batchNo) {
        this(lineId, orderId, sellerSku, locationId, expectedQuantity, receivedQuantity, unitCost, batchNo,
                null, null, null);
    }
}
