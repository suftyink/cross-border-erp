package com.aidotnet.erp.fba.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.fba.FbaWarehouseShipments;
import com.aidotnet.erp.fba.infrastructure.FbaShipmentRepository;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaShipmentResultMapper;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaWarehouseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FbaShipmentReceiptService implements FbaWarehouseShipments.Receiver {
    private final FbaWarehouseShipments source;
    private final FbaWarehouseMapper locks;
    private final FbaShipmentResultMapper receipts;
    private final FbaShipmentRepository shipments;
    public FbaShipmentReceiptService(FbaWarehouseShipments source, FbaWarehouseMapper locks, FbaShipmentResultMapper receipts, FbaShipmentRepository shipments) {
        this.source = source; this.locks = locks; this.receipts = receipts; this.shipments = shipments;
    }
    @Override
    @Transactional
    public void accept(String tenant, String outbound) {
        var result = source.get(tenant, outbound);
        if (locks.lockShipment(tenant, result.fbaShipmentId()) == null) throw new BizException("FBA_SHIPMENT_NOT_FOUND", "FBA 备货单不存在");
        var existing = receipts.find(tenant, outbound);
        if (existing != null) {
            if (existing.equals(result)) return;
            throw new BizException("FBA_SHIPMENT_RESULT_CONFLICT", "出库凭证与已接收结果不一致");
        }
        var shipment = shipments.find(tenant, result.fbaShipmentId()).orElseThrow();
        if (!shipment.status().name().equals("SUBMITTED") || shipment.plannedQuantity() != result.quantity()
                || !shipment.amazonShipmentId().equals(result.amazonShipmentId()) || shipment.cartonCount() != result.cartonCount()) {
            throw new BizException("FBA_SHIPMENT_RESULT_INVALID", "出库数量、货件号或箱数与 FBA 备货单不一致");
        }
        receipts.insert(tenant, result);
        receipts.updateShipment(tenant, result);
    }
}
