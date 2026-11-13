package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.PurchaseReceiptOperations;
import com.aidotnet.erp.wms.domain.InboundOrder;
import com.aidotnet.erp.wms.domain.InboundOrderLine;
import com.aidotnet.erp.wms.domain.InboundOrderStatus;
import com.aidotnet.erp.wms.domain.InventoryMovement;
import com.aidotnet.erp.wms.domain.MovementType;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseReceiptService implements PurchaseReceiptOperations {

    public static final String REFERENCE_TYPE = "PURCHASE_RECEIPT";
    private final WmsOrderStore orders;
    private final InventoryStore inventory;

    public PurchaseReceiptService(WmsOrderStore orders, InventoryStore inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    @Override
    public Optional<Receipt> findReceipt(String tenantId, String receiptId) {
        return orders.findInboundOrder(tenantId, receiptId)
                .filter(order -> REFERENCE_TYPE.equals(order.referenceType()))
                .map(this::toReceipt);
    }

    @Override
    public List<Receipt> listReceipts(String tenantId, String purchaseOrderId) {
        return orders.listPurchaseReceipts(tenantId, purchaseOrderId).stream().map(this::toReceipt).toList();
    }

    @Override
    public List<ReceiptMovement> listReceiptMovements(String tenantId, String receiptId) {
        return orders.listPurchaseReceiptMovements(tenantId, receiptId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt receive(String tenantId, String purchaseOrderId, String receiptId, String warehouseId,
                           String operatorId, List<DeliveryLine> lines) {
        inventory.lockReceivingWarehouse(tenantId, warehouseId);
        Instant receivedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        InboundOrder order = new InboundOrder(receiptId, tenantId, warehouseId, REFERENCE_TYPE, purchaseOrderId,
                InboundOrderStatus.COMPLETED, null, receivedAt, receivedAt, operatorId);
        if (!orders.insertPurchaseReceipt(order)) {
            throw new BizException("RECEIPT_CONFLICT", "该收货编号已用于另一笔收货");
        }
        for (int index = 0; index < lines.size(); index++) {
            DeliveryLine line = lines.get(index);
            String batchNo = UUID.randomUUID().toString();
            orders.saveInboundOrderLine(new InboundOrderLine(batchNo, receiptId, line.sellerSku(), null,
                    line.quantity(), line.quantity(), line.unitCost(), batchNo, line.purchaseOrderLineId(),
                    line.supplierBatchNo(), index));
            inventory.receivePendingInspection(tenantId, warehouseId, line.sellerSku(), line.quantity(),
                    receiptId, receivedAt);
            orders.saveMovement(new InventoryMovement(batchNo, tenantId, warehouseId,
                    line.sellerSku(), null, null, line.quantity(), MovementType.RECEIVING,
                    REFERENCE_TYPE, receiptId, receivedAt));
        }
        return toReceipt(order);
    }

    private Receipt toReceipt(InboundOrder order) {
        List<ReceiptLine> lines = orders.listInboundOrderLines(order.orderId()).stream()
                .map(line -> new ReceiptLine(line.purchaseOrderLineId(), line.sellerSku(), line.supplierBatchNo(),
                        line.batchNo(), line.receivedQuantity()))
                .toList();
        return new Receipt(order.orderId(), order.referenceId(), order.warehouseId(), order.operatorId(),
                order.createdAt(), lines);
    }
}
