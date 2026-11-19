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
    int registerInternalCode(@Param("tenant") String tenant, @Param("carton") String carton, @Param("code") String code);
    int registerFbaCode(@Param("tenant") String tenant, @Param("carton") String carton, @Param("code") String code);
    List<CartonRecord> list(@Param("tenant") String tenant, @Param("filter") Filter filter);
    void releaseCancelledBinding(@Param("tenant") String tenant, @Param("box") String box);
    int bind(@Param("tenant") String tenant, @Param("carton") String carton, @Param("box") String box,
             @Param("operator") String operator, @Param("at") Instant at);
    record CartonRecord(String cartonId, String requestId, String cartonCode, String requestedCode, String outboundId,
                        String fbaShipmentId, String status, String operatorId, Instant createdAt,
                        String fbaBoxNumber, boolean bindingActive, String boundBy, Instant boundAt) {
        public CartonRecord(String cartonId, String requestId, String cartonCode, String requestedCode, String outboundId,
                            String fbaShipmentId, String status, String operatorId, Instant createdAt) {
            this(cartonId, requestId, cartonCode, requestedCode, outboundId, fbaShipmentId, status, operatorId, createdAt, null, false, null, null);
        }
    }
    record Filter(String outboundId, String cartonCode, String batchNo, String purchaseOrderId) {}
}
