package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.fba.FbaWarehouseShipments;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface FbaDispatchMapper {
    DispatchRecord find(@Param("tenant") String tenant, @Param("outbound") String outbound);
    void insert(@Param("tenant") String tenant, @Param("r") DispatchRecord record);
    String lock(@Param("tenant") String tenant, @Param("outbound") String outbound);
    void synced(@Param("tenant") String tenant, @Param("outbound") String outbound);
    void failed(@Param("tenant") String tenant, @Param("outbound") String outbound);
    List<Pending> pending();
    record Pending(String tenantId, String outboundId) {}
    record DispatchRecord(String outboundId, String fbaShipmentId, String warehouseId, String amazonShipmentId,
                          int quantity, int cartonCount, String carrierCode, String trackingNo, String operatorId, Instant shippedAt,
                          String status, String syncStatus, String syncError) {
        public FbaWarehouseShipments.Shipment proof() {
            return new FbaWarehouseShipments.Shipment(outboundId, fbaShipmentId, warehouseId, amazonShipmentId, quantity,
                    cartonCount, carrierCode, trackingNo, operatorId, shippedAt);
        }
    }
}
