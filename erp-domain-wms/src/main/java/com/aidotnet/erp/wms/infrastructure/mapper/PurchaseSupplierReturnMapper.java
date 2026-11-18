package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.wms.PurchaseSupplierReturns.SupplierReturn;
import com.aidotnet.erp.wms.domain.BatchStock;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PurchaseSupplierReturnMapper {
    List<BatchStock> pendingIsolation(@Param("tenantId") String tenantId, @Param("purchaseOrderId") String purchaseOrderId);

    void recordIsolation(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo, @Param("quantity") int quantity);

    long pendingReturnQuantity(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                               @Param("locationId") String locationId);

    int insertReturn(@Param("tenantId") String tenantId, @Param("document") SupplierReturn document);

    SupplierReturn findReturn(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                              @Param("lock") boolean lock);

    List<SupplierReturn> listReturns(@Param("tenantId") String tenantId, @Param("purchaseOrderId") String purchaseOrderId);

    void shipReturn(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                    @Param("quantity") int quantity, @Param("operatorId") String operatorId, @Param("shippedAt") Instant shippedAt);

    void recordShippedQuantity(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                               @Param("quantity") int quantity);

    int deductLocation(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                       @Param("locationId") String locationId, @Param("quantity") int quantity);
}
