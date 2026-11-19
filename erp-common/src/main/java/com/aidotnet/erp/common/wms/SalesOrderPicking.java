package com.aidotnet.erp.common.wms;

import java.time.Instant;
import java.util.List;

/** 模块化单体内的销售订单下发契约，由 WMS 负责预占和实物批次。 */
public interface SalesOrderPicking {
    Work dispatch(String tenantId, String salesOrderId, String warehouseId, String operatorId, List<Line> lines);
    boolean exists(String tenantId, String salesOrderId);

    record Line(String lineId, String sellerSku, int quantity) {}
    record Work(String outboundId, String salesOrderId, String warehouseId, String status,
                List<Allocation> allocations, List<ActualPick> picks) {}
    record Allocation(String allocationId, String outboundId, String lineId, String salesLineId, String sellerSku,
                      String locationId, String locationCode, String batchNo, String receiptId, String purchaseOrderId,
                      int plannedQuantity, int pickedQuantity) {}
    record ActualPick(String requestId, String outboundId, String allocationId, String locationId, String locationCode,
                      String batchNo, String sellerSku, String receiptId, String purchaseOrderId, int quantity,
                      String operatorId, Instant createdAt) {}
}
