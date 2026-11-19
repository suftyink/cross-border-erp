package com.aidotnet.erp.fba.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 显式租户条件，确保行锁位于租户过滤之后。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface FbaWarehouseMapper {
    @Select("SELECT fba_shipment_id FROM fba_shipment WHERE tenant_id = #{tenant} AND fba_shipment_id = #{id} FOR UPDATE")
    String lockShipment(@Param("tenant") String tenant, @Param("id") String shipmentId);
}
