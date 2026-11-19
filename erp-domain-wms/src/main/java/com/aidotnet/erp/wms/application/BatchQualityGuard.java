package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper;
import org.springframework.stereotype.Component;

/** 写操作须在获得相同仓库/SKU 余额锁后校验，避免与发起更正并发穿透。 */
@Component
public class BatchQualityGuard {
    private final QualityCorrectionMapper corrections;
    public BatchQualityGuard(QualityCorrectionMapper corrections) { this.corrections = corrections; }
    public void requireClear(String tenant, String batch) {
        if (corrections.held(tenant, batch)) throw new BizException("BATCH_QUALITY_HELD", "该批次正在质检更正，已暂停分配、拣货和发货");
    }
    public boolean retainFoundStock(String tenant, String batch, int missing) {
        if (!corrections.held(tenant, batch)) return false;
        if (corrections.excludeMissing(tenant, batch, missing) != 1) throw new BizException("QUALITY_CORRECTION_REVIEW_CONFLICT", "请核对更正与差异复核状态");
        return true;
    }
    public boolean heldOutbound(String tenant, String outbound) { return corrections.heldOutbound(tenant, outbound); }
}
