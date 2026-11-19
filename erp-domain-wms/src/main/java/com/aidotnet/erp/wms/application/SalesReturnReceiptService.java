package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.crm.SalesReturnOrders;
import com.aidotnet.erp.common.crm.SalesReturnOrders.Receipt;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesReturnReceiptMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesReturnReceiptService {
    private final SalesReturnOrders orders;
    private final SalesReturnReceiptMapper receipts;
    private final InventoryStore inventory;
    public SalesReturnReceiptService(SalesReturnOrders orders, SalesReturnReceiptMapper receipts, InventoryStore inventory) {
        this.orders = orders; this.receipts = receipts; this.inventory = inventory;
    }
    public List<SalesReturnOrders.Order> work(String tenant) { return orders.list(tenant); }
    public List<Receipt> list(String tenant, String id) {
        orders.getOrder(tenant, id);
        return receipts.list(tenant, id);
    }
    @Transactional
    public Receipt receive(String tenant, String id, String receiptId, String batch, int quantity, boolean unidentified, String locationCode, String reason, String operator) {
        var order = orders.lock(tenant, id);
        Receipt existing = receipts.find(tenant, receiptId);
        if (existing != null) {
            if (existing.returnId().equals(id) && Objects.equals(existing.batchNo(), batch) && existing.quantity() == quantity
                    && unidentified == (existing.batchNo() == null) && Objects.equals(existing.quarantineLocationCode(), normalized(locationCode))
                    && Objects.equals(existing.unidentifiedReason(), normalized(reason))) return existing;
            throw new BizException("SALES_RETURN_REQUEST_CONFLICT", "收货编号已用于不同的退货实收");
        }
        if (unidentified ? batch != null || normalized(locationCode) == null || normalized(reason) == null
                : batch == null || normalized(locationCode) != null || normalized(reason) != null) {
            throw new BizException("SALES_RETURN_RECEIPT_INVALID", "批次不明时须填写隔离库位及原因；已知批次须扫描实物批次码");
        }
        if (!unidentified && !order.expectedBatchNo().equals(batch)) throw new BizException("SALES_RETURN_BATCH_MISMATCH", "实物批次与目标召回批次不符");
        if (quantity <= 0 || quantity > order.quantity() - order.receivedQuantity()) throw new BizException("SALES_RETURN_RECEIPT_EXCEEDED", "本次实收超过该退货单剩余应收数量");
        if (inventory.findWarehouse(tenant, order.warehouseId()).isEmpty()) throw new BizException("WAREHOUSE_NOT_FOUND", "退货仓库不存在");
        WarehouseLocation location = null;
        if (unidentified) {
            var matches = inventory.lockLocationsByCode(tenant, order.warehouseId(), locationCode.strip());
            if (matches.size() != 1 || !matches.get(0).enabled() || matches.get(0).purpose() != WarehouseLocation.Purpose.QUARANTINE) {
                throw new BizException("SALES_RETURN_QUARANTINE_INVALID", "请扫描退货仓库中唯一、已启用的隔离库位");
            }
            location = matches.get(0);
        }
        Receipt receipt = new Receipt(receiptId, id, batch, quantity, operator, Instant.now(), null, null, null,
                location == null ? null : location.locationId(), location == null ? null : location.locationCode(), normalized(reason));
        receipts.insert(tenant, order, receipt);
        inventory.receiveSalesReturn(tenant, order, receipt);
        orders.recordReceipt(tenant, receipt);
        return receipt;
    }
    private String normalized(String text) { return text == null || text.isBlank() ? null : text.strip(); }
}
