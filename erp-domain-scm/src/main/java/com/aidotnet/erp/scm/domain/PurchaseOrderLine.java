package com.aidotnet.erp.scm.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        int receivingLimit,
        @JsonIgnore ReplacementProgress replacement,
        int replacementReceivableQuantity
) {
    public record ReplacementProgress(int pending, int received, int toleranceUsed, int amendmentApplied) {
        public static final ReplacementProgress NONE = new ReplacementProgress(0, 0, 0, 0);
    }

    public PurchaseOrderLine(String lineId, String productId, String sellerSku, int quantity, int receivedQuantity,
                             BigDecimal unitCost, BigDecimal totalPrice, Instant expectedDate) {
        this(lineId, productId, sellerSku, quantity, receivedQuantity, unitCost, totalPrice, expectedDate,
                quantity, 0, quantity, ReplacementProgress.NONE, 0);
    }

    public long getPendingQuantity() {
        return (long) getNormalPendingQuantity() + replacement.pending();
    }

    public int getNormalPendingQuantity() {
        return Math.max(0, quantity - ordinaryProgress());
    }

    public int getNormalReceivableQuantity() {
        return Math.max(0, receivingLimit - ordinaryProgress());
    }

    public int ordinaryProgress() {
        return getOriginalReceivedQuantity() + replacement.amendmentApplied();
    }

    public int getFulfillmentQuantity() {
        return ordinaryProgress();
    }

    public int getOriginalReceivedQuantity() {
        return receivedQuantity - replacement.received();
    }

    public int getReplacementReceivedQuantity() {
        return replacement.received();
    }

    public int getReplacementPendingQuantity() {
        return replacement.pending();
    }

    public int getOverReceivedQuantity() {
        return ordinaryOverReceived() + replacement.toleranceUsed();
    }

    private int ordinaryOverReceived() {
        return overReceivedBeforeAmendment + Math.max(0, ordinaryProgress() - quantity);
    }

    public PurchaseOrderLine withReceivingLimit(BigDecimal percent) {
        long originalAllowance = BigDecimal.valueOf(originalQuantity).multiply(percent).movePointLeft(2)
                .setScale(0, RoundingMode.DOWN).longValueExact();
        long ordinaryAllowance = originalQuantity != quantity ? 0 : Math.max(0, originalAllowance - replacement.toleranceUsed());
        int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(ordinaryProgress(), quantity + ordinaryAllowance));
        long unusedTolerance = Math.max(0, originalAllowance - getOverReceivedQuantity());
        int replacementLimit = replacement.pending() == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE - receivedQuantity,
                replacement.pending() + unusedTolerance + amendmentRoom());
        return new PurchaseOrderLine(lineId, productId, sellerSku, quantity, receivedQuantity, unitCost, totalPrice,
                expectedDate, originalQuantity, overReceivedBeforeAmendment, limit, replacement, replacementLimit);
    }

    public PurchaseOrderLine receive(int cumulativeQuantity) {
        return withReceiptProgress(cumulativeQuantity, replacement);
    }

    public PurchaseOrderLine expectReplacement(int count) {
        return withReceiptProgress(receivedQuantity, new ReplacementProgress(Math.addExact(replacement.pending(), count),
                replacement.received(), replacement.toleranceUsed(), replacement.amendmentApplied()));
    }

    public PurchaseOrderLine receiveReplacement(int count) {
        int credit = Math.min(count, replacement.pending());
        int excess = count - credit;
        int applied = Math.min(excess, amendmentRoom());
        return withReceiptProgress(receivedQuantity + count, new ReplacementProgress(replacement.pending() - credit,
                replacement.received() + count, replacement.toleranceUsed() + excess - applied,
                replacement.amendmentApplied() + applied));
    }

    private int amendmentRoom() {
        return Math.max(0, quantity - Math.max(originalQuantity, ordinaryProgress()));
    }

    private PurchaseOrderLine withReceiptProgress(int cumulativeQuantity, ReplacementProgress progress) {
        return new PurchaseOrderLine(lineId, productId, sellerSku, quantity, cumulativeQuantity, unitCost,
                totalPrice, expectedDate, originalQuantity, overReceivedBeforeAmendment, receivingLimit,
                progress, replacementReceivableQuantity);
    }

    public PurchaseOrderLine amend(int revisedQuantity) {
        return new PurchaseOrderLine(lineId, productId, sellerSku, revisedQuantity, receivedQuantity, unitCost,
                unitCost.multiply(BigDecimal.valueOf(revisedQuantity)), expectedDate,
                originalQuantity, ordinaryOverReceived(), revisedQuantity, replacement, replacementReceivableQuantity);
    }

    /** 计算行总价，totalPrice为空时按单价×数量计算 */
    public BigDecimal lineTotal() {
        return totalPrice != null ? totalPrice : unitCost.multiply(BigDecimal.valueOf(quantity));
    }
}
