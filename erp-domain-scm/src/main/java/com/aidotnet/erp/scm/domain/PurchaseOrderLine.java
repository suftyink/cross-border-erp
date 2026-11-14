package com.aidotnet.erp.scm.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 采购订单行领域模型
 * <p>
 * 描述: 采购订单中的单个商品行，记录SKU、数量、单价和收货数量。
 * </p>
 *
 * @author ERP系统
 */
public record PurchaseOrderLine(
        String lineId,
        String productId,
        String sellerSku,
        int quantity,
        int receivedQuantity,
        BigDecimal unitCost,
        BigDecimal totalPrice,
        Instant expectedDate,
        int originalQuantity,
        int overReceivedBeforeAmendment,
        int receivingLimit
) {
    public PurchaseOrderLine(String lineId, String productId, String sellerSku, int quantity, int receivedQuantity,
                             BigDecimal unitCost, BigDecimal totalPrice, Instant expectedDate) {
        this(lineId, productId, sellerSku, quantity, receivedQuantity, unitCost, totalPrice, expectedDate,
                quantity, 0, quantity);
    }

    public int getPendingQuantity() {
        return Math.max(0, quantity - receivedQuantity);
    }

    public int getOverReceivedQuantity() {
        return overReceivedBeforeAmendment + Math.max(0, receivedQuantity - quantity);
    }

    public PurchaseOrderLine withReceivingLimit(BigDecimal percent, boolean amended) {
        int limit = amended ? Math.max(quantity, receivedQuantity) : calculateReceivingLimit(quantity, percent);
        return new PurchaseOrderLine(lineId, productId, sellerSku, quantity, receivedQuantity, unitCost,
                totalPrice, expectedDate, originalQuantity, overReceivedBeforeAmendment, limit);
    }

    public PurchaseOrderLine receive(int cumulativeQuantity) {
        return new PurchaseOrderLine(lineId, productId, sellerSku, quantity, cumulativeQuantity, unitCost,
                totalPrice, expectedDate, originalQuantity, overReceivedBeforeAmendment, receivingLimit);
    }

    public PurchaseOrderLine amend(int revisedQuantity) {
        return new PurchaseOrderLine(lineId, productId, sellerSku, revisedQuantity, receivedQuantity, unitCost,
                unitCost.multiply(BigDecimal.valueOf(revisedQuantity)), expectedDate,
                originalQuantity, getOverReceivedQuantity(), revisedQuantity);
    }

    private static int calculateReceivingLimit(int quantity, BigDecimal percent) {
        return BigDecimal.valueOf(quantity).add(BigDecimal.valueOf(quantity).multiply(percent).movePointLeft(2))
                .min(BigDecimal.valueOf(Integer.MAX_VALUE)).setScale(0, RoundingMode.DOWN).intValueExact();
    }

    /** 计算行总价，totalPrice为空时按单价×数量计算 */
    public BigDecimal lineTotal() {
        return totalPrice != null ? totalPrice : unitCost.multiply(BigDecimal.valueOf(quantity));
    }
}
