package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaDispatchMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "erp.wms.fba-shipment-sync-enabled", havingValue = "true", matchIfMissing = true)
public class FbaShipmentRetryJob {
    private final FbaDispatchMapper dispatch;
    private final FbaShipmentDelivery delivery;
    public FbaShipmentRetryJob(FbaDispatchMapper dispatch, FbaShipmentDelivery delivery) { this.dispatch = dispatch; this.delivery = delivery; }
    @Scheduled(fixedDelayString = "${erp.wms.fba-shipment-sync-delay-ms:15000}", initialDelay = 15000)
    public void retryPending() {
        String previous = TenantContext.getTenantId();
        for (var pending : dispatch.pending()) {
            try { TenantContext.setTenantId(pending.tenantId()); delivery.sync(pending.tenantId(), pending.outboundId()); }
            finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
        }
    }
}
