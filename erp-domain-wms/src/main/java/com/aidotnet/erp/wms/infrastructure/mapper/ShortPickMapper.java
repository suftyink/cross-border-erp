package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.wms.SalesOrderPicking.Allocation;
import com.aidotnet.erp.wms.domain.ShortPickCase;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ShortPickMapper {
    String outbound(@Param("tenantId") String tenantId, @Param("shortPickId") String shortPickId);
    ShortPickCase find(@Param("tenantId") String tenantId, @Param("shortPickId") String shortPickId);
    List<ShortPickCase> list(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId);
    void insert(@Param("tenantId") String tenantId, @Param("outboundId") String outboundId,
                @Param("id") String id, @Param("allocationId") String allocationId, @Param("quantity") int quantity,
                @Param("reason") String reason, @Param("operatorId") String operatorId, @Param("at") Instant at);
    int markAllocationShort(@Param("tenantId") String tenantId, @Param("allocationId") String allocationId, @Param("quantity") int quantity);
    int freezeReview(@Param("tenantId") String tenantId, @Param("a") Allocation allocation, @Param("quantity") int quantity);
    void replace(@Param("tenantId") String tenantId, @Param("id") String id, @Param("requestId") String requestId,
                 @Param("allocationId") String allocationId, @Param("operatorId") String operatorId, @Param("at") Instant at);
    void count(@Param("tenantId") String tenantId, @Param("id") String id, @Param("found") int found,
               @Param("missing") int missing, @Param("remark") String remark, @Param("operatorId") String operatorId, @Param("at") Instant at);
    int resolveStock(@Param("tenantId") String tenantId, @Param("c") ShortPickCase review);
    void confirm(@Param("tenantId") String tenantId, @Param("id") String id, @Param("operatorId") String operatorId, @Param("at") Instant at);
    int physical(@Param("tenantId") String tenantId, @Param("batchNo") String batchNo, @Param("locationId") String locationId);
}
