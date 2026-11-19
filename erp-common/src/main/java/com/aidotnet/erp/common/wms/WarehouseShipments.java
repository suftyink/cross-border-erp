package com.aidotnet.erp.common.wms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** WMS 提供不可变的实际发货结果；OMS 读取结果，不发起库存扣减。 */
public interface WarehouseShipments {
    Shipment get(String tenantId, String packageId);
    List<Shipment> forOrder(String tenantId, String salesOrderId);

    interface Receiver {
        void accept(String tenantId, String packageId);
    }
    record Shipment(String packageId, String outboundId, String salesOrderId, String warehouseId,
                    String status, BigDecimal weightKg, String carrierCode, String trackingNo,
                    Instant shippedAt, String operatorId, String omsSyncStatus, String syncError,
                    List<Line> lines) {}
    record Line(String pickId, String allocationId, String outboundLineId, String salesLineId,
                String sellerSku, String locationId, String locationCode, String batchNo,
                String receiptId, String purchaseOrderId, int quantity) {}
    record Trace(String packageId, String outboundId, String salesOrderId, String warehouseId,
                 String pickId, String salesLineId, String sellerSku, String locationId, String locationCode,
                 String batchNo, String receiptId, String purchaseOrderId, int quantity,
                 Instant shippedAt, String operatorId) {}
}
