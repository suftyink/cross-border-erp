package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.wms.WarehouseShipments.Line;
import com.aidotnet.erp.common.wms.WarehouseShipments.Trace;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SalesDispatchMapper {
    String packageOutbound(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    int packedQuantity(@Param("tenantId") String tenantId, @Param("pickId") String pickId);
    void insertPick(@Param("tenantId") String tenantId, @Param("packageId") String packageId,
                    @Param("pickId") String pickId, @Param("quantity") int quantity);
    List<Line> lines(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    int deduct(@Param("tenantId") String tenantId, @Param("line") Line line);
    int physicalQuantity(@Param("tenantId") String tenantId, @Param("line") Line line);
    int shippedQuantity(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    void insertShipment(@Param("tenantId") String tenantId, @Param("packageId") String packageId,
                        @Param("outboundId") String outboundId, @Param("operatorId") String operatorId, @Param("at") Instant at);
    DeliveryState state(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    String lockShipment(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    void markSynced(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    void markFailed(@Param("tenantId") String tenantId, @Param("packageId") String packageId);
    List<PendingDelivery> pending();
    List<String> packagesForOrder(@Param("tenantId") String tenantId, @Param("salesOrderId") String salesOrderId);
    List<Trace> trace(@Param("tenantId") String tenantId, @Param("filter") TraceFilter filter);
    record DeliveryState(String operatorId, String omsSyncStatus, String syncError) {}
    record PendingDelivery(String tenantId, String packageId) {}
    record TraceFilter(String purchaseOrderId, String batchNo, String salesOrderId, String outboundId, String packageId,
                       String warehouseId, String locationId, String sellerSku) {}
}
