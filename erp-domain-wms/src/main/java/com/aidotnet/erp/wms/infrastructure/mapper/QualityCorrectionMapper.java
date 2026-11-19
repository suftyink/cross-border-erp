package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.wms.domain.QualityCorrection;
import com.aidotnet.erp.common.wms.SalesOrderPicking.Allocation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QualityCorrectionMapper {
    QualityCorrection find(@Param("tenant") String tenant, @Param("id") String id);
    List<QualityCorrection> list(@Param("tenant") String tenant, @Param("batch") String batch);
    String batchForCase(@Param("tenant") String tenant, @Param("id") String id);
    boolean held(@Param("tenant") String tenant, @Param("batch") String batch);
    int excludeMissing(@Param("tenant") String tenant, @Param("batch") String batch, @Param("missing") int missing);
    List<String> reviewIds(@Param("tenant") String tenant, @Param("id") String id);
    boolean heldOutbound(@Param("tenant") String tenant, @Param("outbound") String outbound);
    List<String> affectedWorks(@Param("tenant") String tenant, @Param("batch") String batch);
    List<Allocation> allocations(@Param("tenant") String tenant, @Param("batch") String batch);
    List<AffectedPick> picks(@Param("tenant") String tenant, @Param("batch") String batch);
    int unpickLocation(@Param("tenant") String tenant, @Param("batch") String batch, @Param("location") String location, @Param("quantity") int quantity);
    void returnPick(@Param("tenant") String tenant, @Param("pick") String pick, @Param("quantity") int quantity);
    void cancelPickedAllocation(@Param("tenant") String tenant, @Param("allocation") String allocation, @Param("quantity") int quantity);
    void reduceLinePicked(@Param("tenant") String tenant, @Param("line") String line, @Param("quantity") int quantity);
    void cancelPackages(@Param("tenant") String tenant, @Param("pick") String pick);
    void recordDirectPending(@Param("tenant") String tenant, @Param("id") String id, @Param("quantity") int quantity);
    void cancelAllocation(@Param("tenant") String tenant, @Param("allocation") String allocation, @Param("quantity") int quantity);
    int releaseLocation(@Param("tenant") String tenant, @Param("batch") String batch, @Param("location") String location, @Param("quantity") int quantity);
    void markReleased(@Param("tenant") String tenant, @Param("id") String id, @Param("operator") String operator, @Param("at") Instant at);
    List<Placement> placements(@Param("tenant") String tenant, @Param("batch") String batch);
    Totals totals(@Param("tenant") String tenant, @Param("batch") String batch);
    void insert(@Param("tenant") String tenant, @Param("id") String id, @Param("batch") String batch,
                @Param("quantity") int quantity, @Param("reason") String reason, @Param("operator") String operator, @Param("at") Instant at);
    Movement movement(@Param("tenant") String tenant, @Param("request") String request);
    void insertMovement(@Param("tenant") String tenant, @Param("id") String id, @Param("m") Movement movement);
    int reverseLocation(@Param("tenant") String tenant, @Param("batch") String batch, @Param("location") String location, @Param("quantity") int quantity);
    void reversePutaway(@Param("tenant") String tenant, @Param("batch") String batch, @Param("quantity") int quantity);
    void recordReversed(@Param("tenant") String tenant, @Param("id") String id, @Param("quantity") int quantity);
    void withdraw(@Param("tenant") String tenant, @Param("id") String id, @Param("operator") String operator, @Param("at") Instant at);
    void reduceGood(@Param("tenant") String tenant, @Param("batch") String batch, @Param("quantity") int quantity);
    void inspect(@Param("tenant") String tenant, @Param("id") String id, @Param("good") int good, @Param("defective") int defective,
                 @Param("operator") String operator, @Param("at") Instant at);
    void restoreDisposition(@Param("tenant") String tenant, @Param("batch") String batch, @Param("good") int good, @Param("defective") int defective);
    record Totals(int quantity, int reserved, int picked, int review, int available) {}
    record Placement(String transactionId, String locationId, String locationCode, int quantity, int reversedQuantity) {}
    record Movement(String requestId, String correctionId, String originalOperationId, String movementType,
                    int quantity, String locationId, String operatorId, Instant createdAt) {}
    record AffectedPick(String requestId, String outboundId, String allocationId, String lineId, String locationId,
                        String locationCode, String batchNo, int quantity, int returnedQuantity, int shippedQuantity) {}
}
