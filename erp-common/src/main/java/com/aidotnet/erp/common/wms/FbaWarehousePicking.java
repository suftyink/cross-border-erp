package com.aidotnet.erp.common.wms;

import java.util.List;

/** FBA 提供备货需求，WMS 使用统一的批次预占及实拣作业。 */
public interface FbaWarehousePicking {
    SalesOrderPicking.Work dispatchFba(String tenant, String shipmentId, String warehouseId, String operator,
                                      List<SalesOrderPicking.Line> lines);
    SalesOrderPicking.Work findFbaWork(String tenant, String shipmentId);
}
