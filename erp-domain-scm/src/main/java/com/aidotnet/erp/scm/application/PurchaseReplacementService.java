package com.aidotnet.erp.scm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.scm.PurchaseReplacementOperations;
import com.aidotnet.erp.scm.domain.PurchaseOrder;
import com.aidotnet.erp.scm.domain.PurchaseOrderLine;
import com.aidotnet.erp.scm.domain.PurchaseOrderStatus;
import com.aidotnet.erp.scm.infrastructure.PurchaseStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PurchaseReplacementService implements PurchaseReplacementOperations {
    private final PurchaseStore store;
    private final PurchaseService purchases;
    private final PurchaseTrackingService tracking;

    public PurchaseReplacementService(PurchaseStore store, PurchaseService purchases, PurchaseTrackingService tracking) {
        this.store = store;
        this.purchases = purchases;
        this.tracking = tracking;
    }

    @Override
    public void lockOrder(String tenantId, String purchaseOrderId) {
        purchases.lockPurchaseOrder(tenantId, purchaseOrderId);
    }

    @Override
    public void recordReturnedForReplacement(String tenantId, String purchaseOrderId, String lineId, int quantity) {
        PurchaseOrder order = purchases.lockPurchaseOrder(tenantId, purchaseOrderId);
        if (!order.canReceive() && order.status() != PurchaseOrderStatus.RECEIVED) {
            throw new BizException("PO_STATUS_INVALID", "原采购单当前状态不可恢复待补");
        }
        PurchaseOrderLine target = order.lines().stream().filter(line -> lineId.equals(line.lineId())).findFirst()
                .orElseThrow(() -> new BizException("PO_LINE_NOT_FOUND", "原采购单行不存在"));
        if ((long) target.getReplacementPendingQuantity() + quantity > Integer.MAX_VALUE - target.receivedQuantity()) {
            throw new BizException("PO_RECEIVE_EXCEEDS_ORDERED", "采购单实收与待补数量超过系统上限");
        }
        PurchaseOrderLine updated = target.expectReplacement(quantity);
        List<PurchaseOrderLine> lines = order.lines().stream().map(line -> lineId.equals(line.lineId()) ? updated : line).toList();
        store.savePurchaseOrder(order.withProgress(PurchaseOrderStatus.PARTIALLY_RECEIVED, lines, order.totalAmount()));
        tracking.syncReplacementProgress(tenantId, purchaseOrderId, updated);
    }
}
