package com.aidotnet.erp.fba.infrastructure.mapper;

import com.aidotnet.erp.common.fba.FbaWarehouseShipments.Shipment;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface FbaShipmentResultMapper {
    Shipment find(@Param("tenant") String tenant, @Param("outbound") String outbound);
    Shipment forShipment(@Param("tenant") String tenant, @Param("shipment") String shipment);
    void insert(@Param("tenant") String tenant, @Param("r") Shipment result);
    void updateShipment(@Param("tenant") String tenant, @Param("r") Shipment result);
}
