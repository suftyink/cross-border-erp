package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 发货事务提交后再回写；回写失败只保留重试凭证，不撤销真实发货。 */
@Service
public class SalesShipmentDelivery {
    private final SalesDispatchMapper dispatch;
    private final WarehouseShipments.Receiver receiver;
    private final SalesDispatchService shipments;
    private final TransactionTemplate transaction;

    public SalesShipmentDelivery(SalesDispatchMapper dispatch, WarehouseShipments.Receiver receiver,
                                 SalesDispatchService shipments, PlatformTransactionManager manager) {
        this.dispatch = dispatch;
        this.receiver = receiver;
        this.shipments = shipments;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public WarehouseShipments.Shipment sync(String tenant, String packageId) {
        if (!"SHIPPED".equals(shipments.get(tenant, packageId).status())) {
            throw new BizException("SALES_PACKAGE_NOT_SHIPPED", "包裹尚未实际发货，不能回写订单");
        }
        try {
            transaction.executeWithoutResult(status -> {
                String state = dispatch.lockShipment(tenant, packageId);
                if ("SYNCED".equals(state)) return;
                if (state == null) throw new BizException("SALES_SHIPMENT_NOT_FOUND", "发货凭证不存在");
                receiver.accept(tenant, packageId);
                dispatch.markSynced(tenant, packageId);
            });
        } catch (RuntimeException failure) {
            transaction.executeWithoutResult(status -> dispatch.markFailed(tenant, packageId));
        }
        return shipments.get(tenant, packageId);
    }
}
