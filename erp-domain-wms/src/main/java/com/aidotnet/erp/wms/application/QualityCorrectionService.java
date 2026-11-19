package com.aidotnet.erp.wms.application;

import static com.aidotnet.erp.wms.domain.InventoryTransaction.TransactionType.*;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import com.aidotnet.erp.wms.domain.QualityCorrection;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.mapper.PurchaseInspectionMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper.Movement;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper.Placement;
import com.aidotnet.erp.wms.infrastructure.mapper.QualityCorrectionMapper.AffectedPick;
import com.aidotnet.erp.common.wms.SalesOrderPicking.Allocation;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualityCorrectionService {
    private final QualityCorrectionMapper corrections;
    private final PurchaseInspectionMapper inspections;
    private final InventoryStore inventory;
    private final SalesPickingMapper picks;

    public QualityCorrectionService(QualityCorrectionMapper corrections, PurchaseInspectionMapper inspections, InventoryStore inventory, SalesPickingMapper picks) {
        this.corrections = corrections;
        this.inspections = inspections;
        this.inventory = inventory;
        this.picks = picks;
    }

    public List<QualityCorrection> list(String tenant, String batch) { return corrections.list(tenant, batch); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail get(String tenant, String id) {
        QualityCorrection c = require(tenant, id);
        return new Detail(c, corrections.placements(tenant, c.batchNo()), corrections.allocations(tenant, c.batchNo()), corrections.picks(tenant, c.batchNo()), corrections.totals(tenant, c.batchNo()).review(), corrections.reviewIds(tenant, id));
    }

    @Transactional
    public Detail pause(String tenant, String id, String batchNo, String reason, String operator) {
        PurchaseInspectionBatch batch = batch(tenant, batchNo);
        InventoryBalance before = balance(tenant, batch);
        var existing = corrections.find(tenant, id);
        if (existing != null) {
            if (existing.batchNo().equals(batchNo) && existing.reason().equals(reason.strip())) return get(tenant, id);
            throw conflict();
        }
        if (corrections.held(tenant, batchNo)) throw new BizException("QUALITY_CORRECTION_ACTIVE", "该批次已有进行中的质检更正");
        var inspection = inspections.selectInspection(tenant, batchNo);
        var totals = corrections.totals(tenant, batchNo);
        if (inspection == null || inspection.disposedAt() == null) throw new BizException("QUALITY_CORRECTION_DISPOSITION_REQUIRED", "请先完成原质检判定");
        int quantity = Math.addExact(totals.quantity(), inspection.goodQuantity() - inspection.putawayQuantity());
        if (quantity <= 0) throw new BizException("QUALITY_CORRECTION_NO_STOCK", "该批次没有仍在库的良品；已发货数量保留原始事实");
        Instant now = Instant.now();
        corrections.insert(tenant, id, batchNo, quantity, reason.strip(), operator, now);
        InventoryBalance after = frozen(before, before.reserved(), Math.addExact(before.frozen(), totals.available()));
        inventory.saveBalance(after);
        movement(tenant, require(tenant, id), operator, QUALITY_HOLD, quantity, before, after, 0, 0, 0,
                -totals.available(), null, null, "暂停分配、拣货及发货；原判定 " + inspection.inspectionId());
        return get(tenant, id);
    }

    @Transactional
    public Detail release(String tenant, String id, String operator) {
        QualityCorrection c = lock(tenant, id);
        if (c.releasedAt() != null) return get(tenant, id);
        if (c.withdrawnAt() != null) throw new BizException("QUALITY_CORRECTION_ALREADY_WITHDRAWN", "已撤销原判定，无待释放预占");
        InventoryBalance before = balance(tenant, batch(tenant, c.batchNo()));
        int released = 0;
        for (Allocation a : corrections.allocations(tenant, c.batchNo())) {
            int quantity = a.plannedQuantity() - a.pickedQuantity() - a.shortQuantity() - a.cancelledQuantity();
            if (quantity == 0) continue;
            if (corrections.releaseLocation(tenant, c.batchNo(), a.locationId(), quantity) != 1) throw stockMismatch();
            corrections.cancelAllocation(tenant, a.allocationId(), quantity);
            InventoryBalance after = frozen(before, before.reserved() - quantity, before.frozen() + quantity);
            inventory.saveBalance(after);
            movement(tenant, c, operator, QUALITY_RELEASE, quantity, before, after, 0, 0, 0, 0,
                    a.locationId(), null, "主管取消原分配 " + a.allocationId() + "；出库单 " + a.outboundId() + "，预占转质量暂停");
            before = after;
            released += quantity;
        }
        if (released == 0) throw new BizException("QUALITY_CORRECTION_NO_RESERVATION", "没有需要主管解除的未拣预占");
        corrections.markReleased(tenant, id, operator, Instant.now());
        return get(tenant, id);
    }

    @Transactional
    public Detail reverse(String tenant, String id, String operator, Reverse command) {
        QualityCorrection c = lock(tenant, id);
        Placement placement = corrections.placements(tenant, c.batchNo()).stream()
                .filter(p -> p.transactionId().equals(command.putawayTransactionId())).findFirst()
                .orElseThrow(() -> new BizException("QUALITY_CORRECTION_SOURCE_INVALID", "上架流水不属于本次原批次"));
        if (!placement.locationCode().equals(command.locationCode().strip())) throw new BizException("QUALITY_CORRECTION_SCAN_INVALID", "实物库位码与原上架作业不符");
        Movement previous = corrections.movement(tenant, command.requestId());
        if (previous != null) {
            if (previous.correctionId().equals(id) && previous.movementType().equals("PUTAWAY_REVERSE")
                    && previous.originalOperationId().equals(placement.transactionId()) && previous.quantity() == command.quantity()) return get(tenant, id);
            throw conflict();
        }
        if (c.withdrawnAt() != null) throw new BizException("QUALITY_CORRECTION_ALREADY_WITHDRAWN", "已撤销判定，不能再次撤销上架");
        if (command.quantity() <= 0 || command.quantity() > placement.quantity() - placement.reversedQuantity()
                || command.quantity() > c.quantity() - c.reversedQuantity() - c.directPendingQuantity()) throw new BizException("QUALITY_CORRECTION_QUANTITY_INVALID", "不能超过原上架作业尚未撤销的数量");
        InventoryBalance before = balance(tenant, batch(tenant, c.batchNo()));
        if (corrections.reverseLocation(tenant, c.batchNo(), placement.locationId(), command.quantity()) != 1) {
            throw new BizException("QUALITY_CORRECTION_RELEASE_REQUIRED", "请先由主管解除未拣预占，并由仓管撤拣；撤销量不能超过库位实物");
        }
        int quantity = command.quantity();
        corrections.reversePutaway(tenant, c.batchNo(), quantity);
        corrections.recordReversed(tenant, id, quantity);
        corrections.insertMovement(tenant, id, new Movement(command.requestId(), id, placement.transactionId(), "PUTAWAY_REVERSE", quantity, placement.locationId(), operator, Instant.now()));
        movement(tenant, c, operator, PUTAWAY_REVERSE, quantity, before, before, 0, quantity, 0, 0,
                placement.locationId(), null, "原上架流水 " + placement.transactionId() + "；实物撤回待上架区");
        return get(tenant, id);
    }

    @Transactional
    public Detail unpick(String tenant, String id, String operator, Unpick command) {
        QualityCorrection c = lock(tenant, id);
        AffectedPick pick = corrections.picks(tenant, c.batchNo()).stream().filter(p -> p.requestId().equals(command.pickId())).findFirst()
                .orElseThrow(() -> new BizException("QUALITY_CORRECTION_SOURCE_INVALID", "实拣记录不属于本次原批次"));
        if (!pick.locationCode().equals(command.locationCode().strip()) || !c.batchNo().equals(command.batchNo())) {
            throw new BizException("QUALITY_CORRECTION_SCAN_INVALID", "原库位或实物批次码不符");
        }
        Movement previous = corrections.movement(tenant, command.requestId());
        if (previous != null) {
            if (previous.correctionId().equals(id) && previous.movementType().equals("SALES_UNPICK")
                    && previous.originalOperationId().equals(command.pickId())) return get(tenant, id);
            throw conflict();
        }
        int quantity = pick.quantity() - pick.returnedQuantity() - pick.shippedQuantity();
        if (c.withdrawnAt() != null || quantity <= 0) throw new BizException("QUALITY_CORRECTION_NO_PICKED_STOCK", "没有尚未发货且未撤拣的实物");
        InventoryBalance before = balance(tenant, batch(tenant, c.batchNo()));
        if (before.reserved() < quantity || corrections.unpickLocation(tenant, c.batchNo(), pick.locationId(), quantity) != 1) throw stockMismatch();
        corrections.returnPick(tenant, pick.requestId(), quantity);
        corrections.cancelPickedAllocation(tenant, pick.allocationId(), quantity);
        corrections.reduceLinePicked(tenant, pick.lineId(), quantity);
        corrections.cancelPackages(tenant, pick.requestId());
        corrections.reversePutaway(tenant, c.batchNo(), quantity);
        corrections.reduceGood(tenant, c.batchNo(), quantity);
        corrections.recordDirectPending(tenant, id, quantity);
        corrections.insertMovement(tenant, id, new Movement(command.requestId(), id, command.pickId(), "SALES_UNPICK", quantity, pick.locationId(), operator, Instant.now()));
        InventoryBalance after = frozen(before, before.reserved() - quantity, before.frozen() + quantity);
        inventory.saveBalance(after);
        movement(tenant, c, operator, SALES_UNPICK, quantity, before, after, quantity, 0, 0, 0, null, null,
                "原实拣 " + pick.requestId() + "；出库单 " + pick.outboundId() + "；实物从发货暂存区直接转待检区，原库位 " + pick.locationCode());
        return get(tenant, id);
    }

    @Transactional
    public Detail withdraw(String tenant, String id, String operator) {
        QualityCorrection c = lock(tenant, id);
        if (c.withdrawnAt() != null) return get(tenant, id);
        if (corrections.totals(tenant, c.batchNo()).quantity() != 0) throw new BizException("QUALITY_CORRECTION_PHYSICAL_REQUIRED", "请先撤销上架及撤拣，确认实物全部退回");
        InventoryBalance before = balance(tenant, batch(tenant, c.batchNo()));
        int quantity = c.quantity() - c.directPendingQuantity();
        corrections.reduceGood(tenant, c.batchNo(), quantity);
        corrections.withdraw(tenant, id, operator, Instant.now());
        movement(tenant, c, operator, INSPECTION_WITHDRAW, c.quantity(), before, before, quantity, -quantity, 0, 0,
                null, null, "撤销原判定 " + c.originalInspectionId() + "；待上架转待检，已发货历史保留");
        return get(tenant, id);
    }

    @Transactional
    public Detail inspect(String tenant, String id, String operator, int good, int defective) {
        QualityCorrection c = lock(tenant, id);
        if (c.inspectedAt() != null) {
            if (c.goodQuantity() == good && c.defectiveQuantity() == defective) return get(tenant, id);
            throw conflict();
        }
        if (c.withdrawnAt() == null) throw new BizException("QUALITY_CORRECTION_WITHDRAW_REQUIRED", "质检员须先撤销原判定");
        if (good < 0 || defective < 0 || (long) good + defective != c.quantity()) throw new BizException("QUALITY_CORRECTION_QUANTITY_INVALID", "本次全检良品与次品之和必须等于更正数量");
        InventoryBalance before = balance(tenant, batch(tenant, c.batchNo()));
        corrections.restoreDisposition(tenant, c.batchNo(), good, defective);
        corrections.inspect(tenant, id, good, defective, operator, Instant.now());
        movement(tenant, c, operator, QUALITY_REINSPECT, c.quantity(), before, before, -c.quantity(), good, defective, 0,
                null, null, "更正后全检，良品待上架、次品隔离；原判定 " + c.originalInspectionId());
        return get(tenant, id);
    }

    private QualityCorrection lock(String tenant, String id) {
        String batch = corrections.batchForCase(tenant, id);
        if (batch == null) throw new BizException("QUALITY_CORRECTION_NOT_FOUND", "质检更正单不存在");
        // 与实拣、包裹发货保持作业锁在先；暂停后不再产生新的受影响分配。
        for (String outbound : corrections.affectedWorks(tenant, batch)) picks.lockWork(tenant, outbound);
        batch(tenant, batch);
        return require(tenant, id);
    }
    private QualityCorrection require(String tenant, String id) {
        var c = corrections.find(tenant, id);
        if (c == null) throw new BizException("QUALITY_CORRECTION_NOT_FOUND", "质检更正单不存在");
        return c;
    }
    private PurchaseInspectionBatch batch(String tenant, String batchNo) {
        var batch = inspections.selectBatch(tenant, batchNo, true);
        if (batch == null) throw new BizException("QUALITY_CORRECTION_BATCH_NOT_FOUND", "原收货批次不存在");
        return batch;
    }
    private InventoryBalance balance(String tenant, PurchaseInspectionBatch batch) {
        return inventory.lockBalanceWithPurchaseStates(tenant, batch.warehouseId(), batch.sellerSku())
                .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在"));
    }
    private InventoryBalance frozen(InventoryBalance b, int reserved, int frozen) {
        return new InventoryBalance(b.tenantId(), b.warehouseId(), b.sellerSku(), b.onHand(), reserved, b.inTransit(), frozen, Instant.now());
    }
    private void movement(String tenant, QualityCorrection c, String operator, InventoryTransaction.TransactionType type, int quantity,
            InventoryBalance before, InventoryBalance after, int pendingInspection, int pendingPutaway, int quarantined,
            int available, String source, String target, String remark) {
        inventory.saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenant, c.warehouseId(), c.sellerSku(),
                type, quantity, before.onHand(), before.reserved(), after.onHand(), after.reserved(),
                "QUALITY_CORRECTION", c.correctionId(), remark, Instant.now(), c.batchNo(), operator,
                pendingInspection, pendingPutaway, quarantined, source, target, null, null, null, null, available));
    }
    private BizException conflict() { return new BizException("QUALITY_CORRECTION_REQUEST_CONFLICT", "该操作编号已用于不同的更正内容"); }
    private BizException stockMismatch() { return new BizException("QUALITY_CORRECTION_STOCK_MISMATCH", "批次预占与实物数量不一致，请核查"); }
    public record Reverse(String requestId, String putawayTransactionId, String locationCode, int quantity) {}
    public record Unpick(String requestId, String pickId, String locationCode, String batchNo) {}
    public record Detail(QualityCorrection correction, List<Placement> placements, List<Allocation> allocations, List<AffectedPick> picks, int pendingReviewQuantity, List<String> reviewIds) {}
}
