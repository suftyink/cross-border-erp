package com.aidotnet.erp.fba.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface FbaBoxLabelMapper {
    Registration find(@Param("tenant") String tenant, @Param("shipment") String shipment);
    List<String> numbers(@Param("tenant") String tenant, @Param("shipment") String shipment);
    int insert(@Param("tenant") String tenant, @Param("r") Registration registration);
    int insertNumber(@Param("tenant") String tenant, @Param("shipment") String shipment, @Param("number") String number);
    void updateShipment(@Param("tenant") String tenant, @Param("shipment") String shipment,
                        @Param("amazon") String amazon, @Param("count") int count);
    record Registration(String fbaShipmentId, String amazonShipmentId, int cartonCount, String registeredBy, Instant registeredAt) {}
}
