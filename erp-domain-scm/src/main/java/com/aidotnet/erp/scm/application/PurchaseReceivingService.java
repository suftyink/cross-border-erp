package com.aidotnet.erp.scm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations.DeliveryLine;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations.Receipt;
import com.aidotnet.erp.scm.domain.PurchaseOrder;
import com.aidotnet.erp.scm.domain.PurchaseOrderLine;
import com.aidotnet.erp.scm.infrastructure.PurchaseStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseReceivingService {

    private final PurchaseStore purchaseStore;
    private final PurchaseService purchases;
    private final PurchaseReceiptOperations warehouse;

    public PurchaseReceivingService(PurchaseStore purchaseStore, PurchaseService purchases,
                                    PurchaseReceiptOperations warehouse) {
        this.purchaseStore = purchaseStore;
        this.purchases = purchases;
        this.warehouse = warehouse;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Confirmation confirm(String tenantId, String operatorId, String purchaseOrderId, String receiptId,
                                String warehouseId, List<ArrivalLine> arrivals) {
        PurchaseOrder order = purchaseStore.lockPurchaseOrder(tenantId, purchaseOrderId)
                .orElseThrow(() -> new BizException("PO_NOT_FOUND", "采购单不存在"));
        List<ArrivalLine> lines = mergeBySupplierBatch(arrivals);
        var existing = warehouse.findReceipt(tenantId, receiptId);
        if (existing.isPresent()) {
            Receipt receipt = existing.get();
            List<ArrivalLine> recorded = receipt.lines().stream()
                    .map(line -> new ArrivalLine(line.purchaseOrderLineId(), line.quantity(), line.supplierBatchNo()))
                    .toList();
            if (!receipt.purchaseOrderId().equals(purchaseOrderId) || !receipt.warehouseId().equals(warehouseId)
                    || recorded.size() != lines.size() || !recorded.containsAll(lines)) {
                throw new BizException("RECEIPT_CONFLICT", "该收货编号已用于另一笔收货");
            }
            return new Confirmation(order, receipt);
        }
        if (!order.canReceive()) {
            throw new BizException("PO_STATUS_INVALID", "只有已审批或部分收货的采购单可以收货");
        }
        List<DeliveryLine> delivery = lines.stream().map(line -> {
            PurchaseOrderLine source = order.lines().stream()
                    .filter(candidate -> candidate.lineId().equals(line.lineId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException("PO_LINE_NOT_FOUND", "采购单明细不存在"));
            return new DeliveryLine(source.lineId(), source.sellerSku(), line.supplierBatchNo(),
                    line.quantity(), source.unitCost());
        }).toList();
        PurchaseOrder updated = purchases.recordWarehouseReceipt(order, lines.stream()
                .map(line -> new PurchaseService.ReceiptLine(line.lineId(), line.quantity()))
                .toList());
        Receipt receipt = warehouse.receive(tenantId, purchaseOrderId, receiptId, warehouseId, operatorId, delivery);
        return new Confirmation(updated, receipt);
    }

    private List<ArrivalLine> mergeBySupplierBatch(List<ArrivalLine> arrivals) {
        Map<BatchKey, Long> quantities = new LinkedHashMap<>();
        for (ArrivalLine arrival : arrivals) {
            String supplierBatch = arrival.supplierBatchNo();
            supplierBatch = supplierBatch == null || supplierBatch.isBlank() ? null : supplierBatch.strip();
            quantities.merge(new BatchKey(arrival.lineId(), supplierBatch), (long) arrival.quantity(), Long::sum);
        }
        return quantities.entrySet().stream().map(entry -> {
            if (entry.getValue() > Integer.MAX_VALUE) {
                throw new BizException("PO_RECEIVE_EXCEEDS_ORDERED", "收货数量超过采购数量");
            }
            return new ArrivalLine(entry.getKey().lineId(), entry.getValue().intValue(), entry.getKey().supplierBatchNo());
        }).toList();
    }

    private record BatchKey(String lineId, String supplierBatchNo) {}
    public record ArrivalLine(String lineId, int quantity, String supplierBatchNo) {}
    public record Confirmation(PurchaseOrder purchaseOrder, Receipt receipt) {}
}
