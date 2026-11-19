package com.aidotnet.erp.wms.infrastructure.mapper;

import com.aidotnet.erp.common.crm.SalesReturnOrders.Order;
import com.aidotnet.erp.common.crm.SalesReturnOrders.Receipt;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SalesReturnReceiptMapper {
    String lock(@Param("tenant") String tenant, @Param("receipt") String receipt);
    Context context(@Param("tenant") String tenant, @Param("receipt") String receipt);
    void identify(@Param("tenant") String tenant, @Param("receipt") String receipt, @Param("batch") String batch, @Param("operator") String operator, @Param("at") Instant at);
    record Context(String warehouseId, String sellerSku, String expectedBatchNo) {}
    Receipt find(@Param("tenant") String tenant, @Param("receipt") String receipt);
    List<Receipt> list(@Param("tenant") String tenant, @Param("id") String returnId);
    void insert(@Param("tenant") String tenant, @Param("o") Order order, @Param("r") Receipt receipt);
}
