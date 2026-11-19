package com.aidotnet.erp.common.fba;

import java.time.Instant;
import java.util.List;

/** FBA 维护 Seller Central 箱号，仓储核对归属后确认实际贴标。 */
public interface FbaBoxLabels {
    Plan find(String tenant, String shipmentId);
    record Plan(String fbaShipmentId, String amazonShipmentId, List<String> boxNumbers, String registeredBy, Instant registeredAt) {}
}
