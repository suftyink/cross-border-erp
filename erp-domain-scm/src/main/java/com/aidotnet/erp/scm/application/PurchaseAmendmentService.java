package com.aidotnet.erp.scm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.scm.domain.PurchaseAmendmentLine;
import com.aidotnet.erp.scm.domain.PurchaseOrder;
import com.aidotnet.erp.scm.domain.PurchaseOrderLine;
import com.aidotnet.erp.scm.domain.PurchaseOrderStatus;
import com.aidotnet.erp.scm.infrastructure.PurchaseStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseAmendmentService {

    private final PurchaseStore store;
    private final PurchaseTrackingService tracking;

    public PurchaseAmendmentService(PurchaseStore store, PurchaseTrackingService tracking) {
        this.store = store;
        this.tracking = tracking;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PurchaseOrder amend(String tenantId, String poId, String operatorId, String reason, List<Revision> revisions) {
        PurchaseOrder order = store.lockPurchaseOrder(tenantId, poId)
                .orElseThrow(() -> new BizException("PO_NOT_FOUND", "采购单不存在"));
        if (!order.isAmendable()) {
            throw new BizException("PO_STATUS_INVALID", "采购单须已收齐或有待补数量才可办理此次变更");
        }

        Map<String, PurchaseOrderLine> replacements = new HashMap<>();
        String amendmentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        for (Revision revision : revisions) {
            PurchaseOrderLine oldLine = order.lines().stream().filter(line -> line.lineId().equals(revision.lineId()))
                    .findFirst().orElseThrow(() -> new BizException("PO_LINE_NOT_FOUND", "采购单明细不存在"));
            if (!order.normalReceiptComplete() && oldLine.getReplacementPendingQuantity() == 0) {
                throw new BizException("PO_AMEND_LINE_NOT_ELIGIBLE", "常规收货未完成时，只能变更有待补数量的采购明细");
            }
            if (revision.quantity() <= Math.max(oldLine.quantity(), oldLine.ordinaryProgress())) {
                throw new BizException("PO_AMEND_QUANTITY_INVALID", "变更后订购量必须大于现订购量及订单履约数量");
            }
            PurchaseOrderLine revised = oldLine.amend(revision.quantity());
            if (replacements.putIfAbsent(oldLine.lineId(), revised) != null) {
                throw new BizException("PO_AMEND_DUPLICATE_LINE", "同一次变更不能重复提交同一采购明细");
            }
            store.saveAmendmentLine(tenantId, poId, new PurchaseAmendmentLine(amendmentId, oldLine.lineId(),
                    oldLine.quantity(), revised.quantity(), reason, operatorId, now));
            tracking.updateOrderedQuantity(tenantId, poId, revised);
        }
        List<PurchaseOrderLine> lines = order.lines().stream()
                .map(line -> replacements.getOrDefault(line.lineId(), line)).toList();
        BigDecimal total = lines.stream().map(PurchaseOrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("99999999999999.9999")) > 0) {
            throw new BizException("PO_AMEND_TOTAL_INVALID", "变更后采购金额超出系统上限");
        }
        return store.savePurchaseOrder(order.withProgress(PurchaseOrderStatus.PARTIALLY_RECEIVED, lines, total));
    }

    public List<PurchaseAmendmentLine> history(String tenantId, String poId) {
        return store.listAmendmentLines(tenantId, poId);
    }

    public record Revision(String lineId, int quantity) {}
}
