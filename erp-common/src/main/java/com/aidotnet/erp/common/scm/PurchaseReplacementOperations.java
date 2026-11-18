package com.aidotnet.erp.common.scm;

public interface PurchaseReplacementOperations {
    /** Keep the purchase order locked for the caller's whole return shipment transaction. */
    void lockOrder(String tenantId, String purchaseOrderId);

    void recordReturnedForReplacement(String tenantId, String purchaseOrderId, String lineId, int quantity);
}
