package com.aidotnet.erp.wms.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface FbaCartonMapper {
    CartonRecord findRequest(@Param("tenant") String tenant, @Param("request") String request);
    int insert(@Param("tenant") String tenant, @Param("c") CartonRecord carton);
    List<CartonRecord> list(@Param("tenant") String tenant, @Param("filter") Filter filter);
    record CartonRecord(String cartonId, String requestId, String cartonCode, String requestedCode, String outboundId,
                        String fbaShipmentId, String status, String operatorId, Instant createdAt) {}
    record Filter(String outboundId, String cartonCode, String batchNo, String purchaseOrderId) {}
}
