package com.aidotnet.erp.wms.domain;

import java.time.Instant;

/**
 * 入库单领域模型
 * <p>
 * 描述: 入库单据，关联采购单或退货单，记录入库状态。
 * </p>
 *
 * @author ERP系统
 */
public record InboundOrder(String orderId, String tenantId, String warehouseId, String referenceType,
                           String referenceId, InboundOrderStatus status, String remark,
                           Instant createdAt, Instant updatedAt, String operatorId, boolean replacement) {
    public InboundOrder(String orderId, String tenantId, String warehouseId, String referenceType,
                        String referenceId, InboundOrderStatus status, String remark,
                        Instant createdAt, Instant updatedAt, String operatorId) {
        this(orderId, tenantId, warehouseId, referenceType, referenceId, status, remark, createdAt, updatedAt,
                operatorId, false);
    }
    public InboundOrder(String orderId, String tenantId, String warehouseId, String referenceType,
                        String referenceId, InboundOrderStatus status, String remark,
                        Instant createdAt, Instant updatedAt) {
        this(orderId, tenantId, warehouseId, referenceType, referenceId, status, remark, createdAt, updatedAt, null);
    }
}
