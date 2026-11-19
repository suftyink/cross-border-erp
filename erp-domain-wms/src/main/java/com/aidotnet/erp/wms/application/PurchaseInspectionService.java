package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.domain.PurchaseInspection;
import com.aidotnet.erp.wms.domain.QualityCorrection;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper;
import com.aidotnet.erp.wms.domain.PurchaseInspection.Recheck;
import com.aidotnet.erp.wms.domain.PurchaseInspection.RecheckMethod;
import com.aidotnet.erp.wms.domain.PurchaseInspection.Sample;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchaseInspectionMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseInspectionService {
    private final PurchaseInspectionMapper inspections;
    private final InventoryStore inventory;
    private final QualityCorrectionMapper corrections;

    public PurchaseInspectionService(PurchaseInspectionMapper inspections, InventoryStore inventory, QualityCorrectionMapper corrections) {
        this.inspections = inspections;
        this.inventory = inventory;
        this.corrections = corrections;
    }

    public List<PurchaseInspectionBatch> work(String tenantId, String purchaseOrderId) {
        return inspections.selectWork(tenantId, purchaseOrderId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String tenantId, String batchNo) {
        return detail(tenantId, findBatch(tenantId, batchNo, false), inspections.selectInspection(tenantId, batchNo));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail sample(String tenantId, String batchNo, String operatorId, int checked, int passed, int failed) {
        PurchaseInspectionBatch batch = findBatch(tenantId, batchNo, true);
        PurchaseInspection existing = inspections.selectInspection(tenantId, batchNo);
        if (existing != null) {
            if (existing.sampleChecked() == checked && existing.samplePassed() == passed
                    && existing.sampleFailed() == failed) {
                return detail(tenantId, batchNo);
            }
            throw new BizException("INSPECTION_SAMPLE_RECORDED", "该批次已登记抽检结果");
        }
        validateCounts(batch, checked, passed, failed);
        inspections.insertSample(tenantId, batchNo, UUID.randomUUID().toString(),
                new Sample(checked, passed, failed, operatorId, Instant.now()));
        return detail(tenantId, batchNo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail recheck(String tenantId, String batchNo, String operatorId, RecheckMethod method,
                          int checked, int passed, int failed) {
        PurchaseInspectionBatch batch = findBatch(tenantId, batchNo, true);
        PurchaseInspection existing = requireSample(tenantId, batchNo);
        if (existing.recheckMethod() != null) {
            Recheck previous = existing.recheck();
            if (previous.method() == method && previous.checkedQuantity() == checked
                    && previous.passedQuantity() == passed && previous.failedQuantity() == failed) {
                return detail(tenantId, batchNo);
            }
            throw new BizException("INSPECTION_RECHECK_RECORDED", "该批次已登记复检结果");
        }
        if (existing.sampleFailed() == 0 || existing.disposedAt() != null) {
            throw new BizException("INSPECTION_RECHECK_NOT_REQUIRED", "仅抽检发现次品的未判定批次可复检");
        }
        validateCounts(batch, checked, passed, failed);
        if (method == RecheckMethod.FULL && checked != batch.receivedQuantity()) {
            throw new BizException("INSPECTION_FULL_QUANTITY_INVALID", "全检数量必须等于批次实收数量");
        }
        inspections.updateRecheck(tenantId, batchNo,
                new Recheck(method, checked, passed, failed, operatorId, Instant.now()));
        return detail(tenantId, batchNo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail dispose(String tenantId, String batchNo, String operatorId, int good, int defective) {
        PurchaseInspectionBatch batch = findBatch(tenantId, batchNo, true);
        PurchaseInspection existing = requireSample(tenantId, batchNo);
        if (existing.disposedAt() != null) {
            if (existing.goodQuantity() == good && existing.defectiveQuantity() == defective) {
                return detail(tenantId, batchNo);
            }
            throw new BizException("INSPECTION_ALREADY_DISPOSED", "该批次已确认整批判定");
        }
        if (existing.sampleFailed() > 0 && existing.recheckedAt() == null) {
            throw new BizException("INSPECTION_RECHECK_REQUIRED", "抽检发现次品，须先完成复检或全检");
        }
        if ((long) good + defective != batch.receivedQuantity()) {
            throw new BizException("INSPECTION_DISPOSITION_MISMATCH", "整批良品与次品数量之和必须等于实收数量");
        }
        if (existing.recheckMethod() == RecheckMethod.FULL
                && (existing.recheckPassed() != good || existing.recheckFailed() != defective)) {
            throw new BizException("INSPECTION_FULL_RESULT_MISMATCH", "整批判定须与全检结果一致");
        }
        if (existing.recheckMethod() == RecheckMethod.RECHECK
                && (existing.recheckPassed() > good || existing.recheckFailed() > defective)) {
            throw new BizException("INSPECTION_RECHECK_RESULT_MISMATCH", "整批良品和次品数量不能低于复检已确认的数量");
        }
        Instant now = Instant.now();
        inventory.recordPurchaseDisposition(tenantId, batch, existing.inspectionId(), operatorId, good, defective, now);
        inspections.updateDisposition(tenantId, batchNo, good, defective, operatorId, now);
        return detail(tenantId, batchNo);
    }

    private PurchaseInspectionBatch findBatch(String tenantId, String batchNo, boolean lock) {
        PurchaseInspectionBatch batch = inspections.selectBatch(tenantId, batchNo, lock);
        if (batch == null) {
            throw new BizException("INSPECTION_BATCH_NOT_FOUND", "采购收货批次不存在");
        }
        return batch;
    }

    private PurchaseInspection requireSample(String tenantId, String batchNo) {
        PurchaseInspection inspection = inspections.selectInspection(tenantId, batchNo);
        if (inspection == null) {
            throw new BizException("INSPECTION_SAMPLE_REQUIRED", "请先登记抽检结果");
        }
        return inspection;
    }

    private void validateCounts(PurchaseInspectionBatch batch, int checked, int passed, int failed) {
        if ((long) passed + failed != checked || checked > batch.receivedQuantity()) {
            throw new BizException("INSPECTION_COUNTS_INVALID", "检查数量须等于合格与不合格之和，且不能超过批次实收");
        }
    }

    private Detail detail(String tenantId, PurchaseInspectionBatch batch, PurchaseInspection inspection) {
        boolean disposed = inspection != null && inspection.disposedAt() != null;
        var history = corrections.list(tenantId, batch.batchNo());
        int correctionPending = history.stream().filter(c -> c.inspectedAt() == null)
                .mapToInt(c -> c.withdrawnAt() == null ? c.directPendingQuantity() : c.quantity()).sum();
        return new Detail(batch, inspection == null ? null : inspection.inspectionId(),
                inspection == null ? null : inspection.sample(), inspection == null ? null : inspection.recheck(),
                (disposed ? 0 : batch.receivedQuantity()) + correctionPending,
                disposed ? inspection.goodQuantity() - inspection.putawayQuantity() : 0,
                disposed ? inspection.defectiveQuantity() - inspection.returnedQuantity() : 0,
                disposed ? inspection.dispositionOperatorId() : null,
                disposed ? inspection.disposedAt() : null, history);
    }

    public record Detail(PurchaseInspectionBatch batch, String inspectionId, Sample sample, Recheck recheck,
                         int pendingInspection, int pendingPutaway, int quarantined,
                         String dispositionOperatorId, Instant disposedAt, List<QualityCorrection> qualityCorrections) {}
}
