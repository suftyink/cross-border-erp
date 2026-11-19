package com.aidotnet.erp.common.fba;

import java.time.Instant;

/** WMS 提供持久化的实际整单出库凭证，FBA 按凭证幂等接收。 */
public interface FbaWarehouseShipments {
    Shipment get(String tenant, String outboundId);
    interface Receiver { void accept(String tenant, String outboundId); }
    record Shipment(String outboundId, String fbaShipmentId, String warehouseId, String amazonShipmentId,
                    int quantity, int cartonCount, String carrierCode, String trackingNo, String operatorId, Instant shippedAt) {}
}
