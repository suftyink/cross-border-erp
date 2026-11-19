package com.aidotnet.erp.common.wms;

/** WMS 补充退货批次归属，保持原实收记录和冻结库存。调用方与 WMS 共享事务。 */
public interface SalesReturnIdentification {
    void identify(String tenantId, String returnId, String receiptId, String batchNo, String operatorId);
}
