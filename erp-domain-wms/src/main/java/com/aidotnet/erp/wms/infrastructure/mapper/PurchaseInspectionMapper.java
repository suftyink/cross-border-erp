package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.wms.domain.PurchaseInspection;
import com.aidotnet.erp.wms.domain.PurchaseInspection.Recheck;
import com.aidotnet.erp.wms.domain.PurchaseInspection.Sample;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PurchaseInspectionMapper {
    List<PurchaseInspectionBatch> selectWork(@Param("tenantId") String tenantId,
                                             @Param("purchaseOrderId") String purchaseOrderId);

    PurchaseInspectionBatch selectBatch(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                                        @Param("lock") boolean lock);

    PurchaseInspection selectInspection(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo);

    void insertSample(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                      @Param("inspectionId") String inspectionId, @Param("sample") Sample sample);

    void updateRecheck(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                       @Param("recheck") Recheck recheck);

    void updateDisposition(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo,
                           @Param("good") int good, @Param("defective") int defective,
                           @Param("operatorId") String operatorId, @Param("disposedAt") Instant disposedAt);
}
