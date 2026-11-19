package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.fba.FbaWarehouseShipments;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaDispatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 出库事务提交后回写；失败时保留 WMS 事实和持久化重试凭证。 */
@Service
public class FbaShipmentDelivery {
    private final FbaDispatchMapper dispatch;
    private final FbaDispatchService shipments;
    private final FbaWarehouseShipments.Receiver receiver;
    private final TransactionTemplate transaction;
    public FbaShipmentDelivery(FbaDispatchMapper dispatch, FbaDispatchService shipments, FbaWarehouseShipments.Receiver receiver, PlatformTransactionManager manager) {
        this.dispatch = dispatch; this.shipments = shipments; this.receiver = receiver;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public FbaDispatchMapper.DispatchRecord sync(String tenant, String outbound) {
        shipments.record(tenant, outbound);
        try {
            transaction.executeWithoutResult(status -> {
                String state = dispatch.lock(tenant, outbound);
                if ("SYNCED".equals(state)) return;
                if (state == null) throw new BizException("FBA_SHIPMENT_NOT_DISPATCHED", "整单尚未实际出库");
                receiver.accept(tenant, outbound);
                dispatch.synced(tenant, outbound);
            });
        } catch (RuntimeException failure) {
            transaction.executeWithoutResult(status -> dispatch.failed(tenant, outbound));
        }
        return shipments.record(tenant, outbound);
    }
}
