package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.wms.SalesOrderPicking.Allocation;
import com.aidotnet.erp.common.wms.SalesOrderPicking.ActualPick;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 每条语句显式约束租户，避免租户 SQL 重写改变行锁位置。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SalesPickingMapper {
    String findBySalesOrder(@Param("tenantId") String tenantId, @Param("salesOrderId") String salesOrderId);
    List<String> list(@Param("tenantId") String tenantId, @Param("salesOrderId") String salesOrderId);
    String lockWork(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    void insertWork(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId,
                    @Param("salesOrderId") String salesOrderId, @Param("operatorId") String operatorId,
                    @Param("allowBatchSubstitution") boolean allowBatchSubstitution);
    boolean allowBatchSubstitution(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    List<BatchStock> candidates(@Param("tenantId") String tenantId, @Param("warehouseId") String warehouseId,
                                @Param("sellerSku") String sellerSku);
    int reserve(@Param("tenantId") String tenantId, @Param("stock") BatchStock stock, @Param("quantity") int quantity);
    void insertAllocation(@Param("tenantId") String tenantId, @Param("a") Allocation allocation);
    List<Allocation> allocations(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    List<ActualPick> picks(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    ActualPick findPick(@Param("tenantId") String tenantId, @Param("requestId") String requestId);
    int pick(@Param("tenantId") String tenantId, @Param("a") Allocation allocation, @Param("quantity") int quantity);
    void pickAllocation(@Param("tenantId") String tenantId, @Param("allocationId") String allocationId,
                        @Param("quantity") int quantity);
    void insertPick(@Param("tenantId") String tenantId, @Param("p") ActualPick pick);
    int physicalQuantity(@Param("tenantId") String tenantId, @Param("a") Allocation allocation);
}
