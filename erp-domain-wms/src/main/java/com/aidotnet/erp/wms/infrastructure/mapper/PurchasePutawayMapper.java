package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.StockLedgerFilter;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PurchasePutawayMapper {
    Integer locationQuantity(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                             @Param("locationId") String locationId);

    void addToLocation(@Param("tenantId") String tenantId, @Param("batch") PurchaseInspectionBatch batch,
                       @Param("locationId") String locationId, @Param("quantity") int quantity);

    void recordPutaway(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                       @Param("quantity") int quantity);

    List<BatchStock> balances(@Param("tenantId") String tenantId, @Param("filter") StockLedgerFilter filter,
                              @Param("pendingOnly") boolean pendingOnly);

    String recommendLocation(@Param("tenantId") String tenantId, @Param("warehouseId") String warehouseId,
                             @Param("sellerSku") String sellerSku);
}
