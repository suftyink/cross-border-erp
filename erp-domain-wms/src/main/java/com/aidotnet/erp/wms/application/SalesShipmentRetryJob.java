package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "erp.wms.sales-shipment-sync-enabled", havingValue = "true", matchIfMissing = true)
public class SalesShipmentRetryJob {
    private final SalesDispatchMapper dispatch;
    private final SalesShipmentDelivery delivery;
    public SalesShipmentRetryJob(SalesDispatchMapper dispatch, SalesShipmentDelivery delivery) {
        this.dispatch = dispatch;
        this.delivery = delivery;
    }
    @Scheduled(fixedDelayString = "${erp.wms.sales-shipment-sync-delay-ms:15000}", initialDelay = 15000)
    public void retryPending() {
        String previous = TenantContext.getTenantId();
        for (var pending : dispatch.pending()) {
            try {
                TenantContext.setTenantId(pending.tenantId());
                delivery.sync(pending.tenantId(), pending.packageId());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
            }
        }
    }
}
