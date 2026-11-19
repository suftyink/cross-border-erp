package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.fba.FbaBoxLabels;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaCartonMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FbaCartonBindingService {
    private final SalesPickingMapper locks;
    private final SalesPickingService picking;
    private final FbaCartonMapper cartons;
    private final FbaBoxLabels labels;
    public FbaCartonBindingService(SalesPickingMapper locks, SalesPickingService picking, FbaCartonMapper cartons, FbaBoxLabels labels) {
        this.locks = locks; this.picking = picking; this.cartons = cartons; this.labels = labels;
    }
    @Transactional
    public void bind(String tenant, String outbound, String operator, String cartonCode, String box) {
        if (locks.lockWork(tenant, outbound) == null) throw new BizException("FBA_PICK_NOT_FOUND", "FBA 拣货作业不存在");
        var work = picking.get(tenant, outbound);
        if (!work.sourceType().equals("FBA_SHIPMENT")) throw new BizException("FBA_PICK_NOT_FOUND", "当前作业不是 FBA 备货");
        var carton = cartons.list(tenant, new FbaCartonMapper.Filter(outbound, cartonCode, null, null)).stream()
                .filter(c -> c.cartonCode().equals(cartonCode)).findFirst()
                .orElseThrow(() -> new BizException("FBA_CARTON_SCAN_INVALID", "内部箱号不属于当前备货作业"));
        if (carton.status().equals("CANCELLED")) throw new BizException("FBA_CARTON_CANCELLED", "该箱已作废，请按有效实拣重新装箱");
        if (carton.bindingActive() && box.equals(carton.fbaBoxNumber())) return;
        if (carton.fbaBoxNumber() != null || carton.status().equals("SHIPPED")) throw new BizException("FBA_BOX_ALREADY_BOUND", "该箱已有 FBA 箱号或已经出库，不能改绑");
        var plan = labels.find(tenant, work.sourceId());
        if (plan == null || !plan.boxNumbers().contains(box)) throw new BizException("FBA_BOX_SHIPMENT_MISMATCH", "FBA 箱号未登记或属于其他货件");
        cartons.releaseCancelledBinding(tenant, box);
        if (cartons.registerFbaCode(tenant, carton.cartonId(), box) != 1) {
            boolean occupied = cartons.list(tenant, new FbaCartonMapper.Filter(null, box, null, null)).stream()
                    .anyMatch(c -> box.equals(c.fbaBoxNumber()) && c.bindingActive() && !c.status().equals("CANCELLED"));
            if (occupied) throw new BizException("FBA_BOX_ALREADY_BOUND", "FBA 箱号已占用，请核对内部箱与实际箱标");
            throw new BizException("FBA_BOX_IDENTIFIER_CONFLICT", "该 FBA 箱号与其他箱子的内部箱号冲突");
        }
        if (cartons.bind(tenant, carton.cartonId(), box, operator, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)) != 1) {
            throw new BizException("FBA_BOX_ALREADY_BOUND", "FBA 箱号已占用，请核对内部箱与实际箱标");
        }
    }
}
