package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.SalesReturnIdentification;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesReturnReceiptMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesReturnIdentificationService implements SalesReturnIdentification {
    private final SalesReturnReceiptMapper receipts;
    private final InventoryStore inventory;
    public SalesReturnIdentificationService(SalesReturnReceiptMapper receipts, InventoryStore inventory) { this.receipts = receipts; this.inventory = inventory; }

    @Override
    @Transactional
    public void identify(String tenant, String returnId, String receiptId, String batch, String operator) {
        if (receipts.lock(tenant, receiptId) == null) throw new BizException("SALES_RETURN_RECEIPT_NOT_FOUND", "仓库实收记录不存在");
        var receipt = receipts.find(tenant, receiptId);
        var context = receipts.context(tenant, receiptId);
        if (!returnId.equals(receipt.returnId()) || !context.expectedBatchNo().equals(batch)) throw new BizException("SALES_RETURN_BATCH_MISMATCH", "核实批次与原退货单不符");
        if (receipt.identifiedBatchNo() != null) {
            if (!receipt.identifiedBatchNo().equals(batch)) throw new BizException("SALES_RETURN_BATCH_MISMATCH", "已核实的批次不能修改");
            return;
        }
        if (receipt.batchNo() == null) inventory.identifySalesReturn(tenant, context.warehouseId(), context.sellerSku(), receipt, batch, operator);
        receipts.identify(tenant, receiptId, batch, operator, Instant.now());
    }
}
