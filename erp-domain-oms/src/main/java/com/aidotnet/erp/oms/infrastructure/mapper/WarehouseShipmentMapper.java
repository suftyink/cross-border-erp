package com.aidotnet.erp.oms.infrastructure.mapper;

import com.aidotnet.erp.common.wms.WarehouseShipments.Shipment;
import com.aidotnet.erp.common.wms.WarehouseShipments.Line;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface WarehouseShipmentMapper {
    boolean exists(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    void insert(@Param("tenantId") String tenantId, @Param("s") Shipment shipment);
    void insertLine(@Param("tenantId") String tenantId, @Param("packageId") String packageId, @Param("line") Line line);
    List<LineTotal> totals(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    void updateOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                     @Param("status") String status, @Param("fulfillment") String fulfillment);
    record LineTotal(String salesLineId, String sellerSku, int quantity) {}
}
