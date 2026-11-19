package com.aidotnet.erp.crm.application;

import com.aidotnet.erp.common.crm.SalesReturnOrders;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.crm.infrastructure.mapper.BatchRecallMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesReturnOrderService implements SalesReturnOrders {
    private final BatchRecallMapper returns;
    public SalesReturnOrderService(BatchRecallMapper returns) { this.returns = returns; }

    @Override
    public Order getOrder(String tenant, String id) { return require(tenant, id); }

    @Override
    public List<Order> list(String tenant) { return returns.returns(tenant); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail get(String tenant, String id) { return new Detail(require(tenant, id), returns.receipts(tenant, id)); }

    @Transactional
    public Detail create(String tenant, String recall, String lineId, String id, int quantity, String operator) {
        if (!recall.equals(returns.lockLine(tenant, lineId))) throw new BizException("RECALL_LINE_NOT_FOUND", "明细不属于当前召回单");
        Order existing = returns.returnOrder(tenant, id);
        if (existing != null) {
            if (existing.recallLineId().equals(lineId) && existing.quantity() == quantity) return get(tenant, id);
            throw conflict();
        }
        var line = returns.lines(tenant, recall).stream().filter(l -> l.lineId().equals(lineId)).findFirst().orElseThrow();
        if (quantity <= 0 || quantity > line.expectedQuantity() - line.authorizedQuantity()) throw new BizException("SALES_RETURN_QUANTITY_INVALID", "退货数量不能超过目标批次尚未建退货单的实际发货量");
        returns.insertReturn(tenant, id, line, quantity, operator, Instant.now());
        return get(tenant, id);
    }

    @Override
    @Transactional
    public Order lock(String tenant, String id) {
        String line = returns.lineForReturn(tenant, id);
        if (line == null) throw new BizException("SALES_RETURN_NOT_FOUND", "销售退货单不存在");
        returns.lockLine(tenant, line);
        returns.lockReturn(tenant, id);
        return require(tenant, id);
    }

    @Override
    @Transactional
    public void recordReceipt(String tenant, Receipt receipt) {
        Order order = lock(tenant, receipt.returnId());
        Receipt existing = returns.receipt(tenant, receipt.receiptId());
        if (existing != null) {
            if (existing.returnId().equals(order.returnId()) && existing.batchNo().equals(receipt.batchNo()) && existing.quantity() == receipt.quantity()) return;
            throw conflict();
        }
        if (!order.expectedBatchNo().equals(receipt.batchNo())) throw new BizException("SALES_RETURN_BATCH_MISMATCH", "实物批次与目标召回批次不符");
        if (receipt.quantity() <= 0 || receipt.quantity() > order.quantity() - order.receivedQuantity()) throw new BizException("SALES_RETURN_RECEIPT_EXCEEDED", "本次实收超过该退货单剩余应收数量");
        returns.insertReceipt(tenant, receipt);
        returns.addReceived(tenant, order.returnId(), receipt.quantity());
    }

    @Transactional
    public Detail verify(String tenant, String id, String receiptId, String batch, String operator) {
        Order order = lock(tenant, id);
        Receipt receipt = returns.receipt(tenant, receiptId);
        if (receipt == null || !receipt.returnId().equals(id)) throw new BizException("SALES_RETURN_RECEIPT_NOT_FOUND", "收货记录不属于当前退货单");
        if (!order.expectedBatchNo().equals(batch) || !receipt.batchNo().equals(batch)) throw new BizException("SALES_RETURN_BATCH_MISMATCH", "核实批次与实际退回和目标召回批次不符");
        if (receipt.verifiedAt() != null) return get(tenant, id);
        returns.verify(tenant, receiptId, operator, Instant.now());
        returns.addConfirmed(tenant, id, receipt.quantity());
        return get(tenant, id);
    }

    private Order require(String tenant, String id) {
        Order order = returns.returnOrder(tenant, id);
        if (order == null) throw new BizException("SALES_RETURN_NOT_FOUND", "销售退货单不存在");
        return order;
    }
    private BizException conflict() { return new BizException("SALES_RETURN_REQUEST_CONFLICT", "该操作编号已用于不同的销售退货内容"); }
    public record Detail(Order order, List<Receipt> receipts) {}
}
