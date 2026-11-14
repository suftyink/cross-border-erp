package com.aidotnet.erp.wms.domain;

public record StockLedgerFilter(String warehouseId, String locationId, String sellerSku,
                                String batchNo, String purchaseOrderId, String documentId) {}
