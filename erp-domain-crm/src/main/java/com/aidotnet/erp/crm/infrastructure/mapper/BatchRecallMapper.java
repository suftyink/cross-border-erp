package com.aidotnet.erp.crm.infrastructure.mapper;

import com.aidotnet.erp.crm.domain.BatchRecall;
import com.aidotnet.erp.common.crm.SalesReturnOrders.Order;
import com.aidotnet.erp.common.crm.SalesReturnOrders.Receipt;
import com.aidotnet.erp.common.wms.WarehouseShipments.Trace;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BatchRecallMapper {
    void ensureScope(@Param("tenant") String tenant, @Param("batch") String batch);
    String lockScope(@Param("tenant") String tenant, @Param("batch") String batch);
    boolean sourceClaimed(@Param("tenant") String tenant, @Param("packageId") String packageId, @Param("pick") String pick);
    void insertRecall(@Param("tenant") String tenant, @Param("r") BatchRecall recall);
    BatchRecall recall(@Param("tenant") String tenant, @Param("id") String id);
    List<BatchRecall> list(@Param("tenant") String tenant, @Param("batch") String batch);
    void insertLine(@Param("tenant") String tenant, @Param("line") BatchRecall.Line line);
    List<BatchRecall.Line> lines(@Param("tenant") String tenant, @Param("recall") String recall);
    String lockLine(@Param("tenant") String tenant, @Param("line") String line);
    void insertSource(@Param("tenant") String tenant, @Param("recall") String recall, @Param("line") String line, @Param("s") Trace source);
    List<Trace> sources(@Param("tenant") String tenant, @Param("recall") String recall);
    void insertNotification(@Param("tenant") String tenant, @Param("n") BatchRecall.Notification notification);
    BatchRecall.Notification notification(@Param("tenant") String tenant, @Param("id") String id);
    List<BatchRecall.Notification> notifications(@Param("tenant") String tenant, @Param("recall") String recall);
    void insertReturn(@Param("tenant") String tenant, @Param("id") String id, @Param("line") BatchRecall.Line line,
                      @Param("quantity") int quantity, @Param("operator") String operator, @Param("at") Instant at);
    Order returnOrder(@Param("tenant") String tenant, @Param("id") String id);
    String lineForReturn(@Param("tenant") String tenant, @Param("id") String id);
    String lockReturn(@Param("tenant") String tenant, @Param("id") String id);
    List<Order> returns(@Param("tenant") String tenant);
    Receipt receipt(@Param("tenant") String tenant, @Param("id") String id);
    List<Receipt> receipts(@Param("tenant") String tenant, @Param("id") String id);
    void insertReceipt(@Param("tenant") String tenant, @Param("r") Receipt receipt);
    void addReceived(@Param("tenant") String tenant, @Param("id") String id, @Param("quantity") int quantity);
    void verify(@Param("tenant") String tenant, @Param("id") String id, @Param("operator") String operator, @Param("at") Instant at);
    void addConfirmed(@Param("tenant") String tenant, @Param("id") String id, @Param("quantity") int quantity);
}
