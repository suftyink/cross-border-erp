package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.SalesOrderPicking.Allocation;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.OutboundOrderStatus;
import com.aidotnet.erp.wms.domain.ShortPickCase;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.ShortPickMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortPickService {
    private final ShortPickMapper reviews;
    private final SalesPickingMapper picks;
    private final InventoryStore inventory;
    private final WmsOrderStore orders;
    public ShortPickService(ShortPickMapper reviews, SalesPickingMapper picks, InventoryStore inventory, WmsOrderStore orders) {
        this.reviews = reviews;
        this.picks = picks;
        this.inventory = inventory;
        this.orders = orders;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ShortPickCase> list(String tenant, String outboundId) { return reviews.list(tenant, outboundId); }

    public ShortPickCase get(String tenant, String id) {
        var review = reviews.find(tenant, id);
        if (review == null) throw new BizException("SHORT_PICK_NOT_FOUND", "缺拣复核记录不存在");
        return review;
    }

    @Transactional
    public ShortPickCase report(String tenant, String outbound, String operator, Report command) {
        lockWork(tenant, outbound);
        ShortPickCase existing = reviews.find(tenant, command.shortPickId());
        String reason = command.reason().strip();
        if (existing != null) {
            if (existing.outboundId().equals(outbound) && existing.allocationId().equals(command.allocationId())
                    && existing.shortQuantity() == command.quantity() && existing.reason().equals(reason)) return existing;
            throw conflict();
        }
        requireActive(tenant, outbound);
        Allocation a = picks.allocations(tenant, outbound).stream().filter(v -> v.allocationId().equals(command.allocationId()))
                .findFirst().orElseThrow(() -> new BizException("SHORT_PICK_ALLOCATION_INVALID", "原分配明细不属于当前作业"));
        int quantity = command.quantity();
        if (quantity <= 0 || quantity > a.plannedQuantity() - a.pickedQuantity() - a.shortQuantity()) {
            throw new BizException("SHORT_PICK_QUANTITY_INVALID", "缺拣数量不能超过原明细尚未实拣的数量");
        }
        String warehouse = orders.findOutboundOrder(tenant, outbound).orElseThrow().warehouseId();
        lockLocation(tenant, warehouse, a.locationCode(), a.locationId(), false);
        InventoryBalance before = balance(tenant, warehouse, a.sellerSku());
        Instant now = Instant.now();
        try { reviews.insert(tenant, outbound, command.shortPickId(), a.allocationId(), quantity, reason, operator, now); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        int physical = reviews.physical(tenant, a.batchNo(), a.locationId());
        if (before.reserved() < quantity || reviews.markAllocationShort(tenant, a.allocationId(), quantity) != 1
                || reviews.freezeReview(tenant, a, quantity) != 1) throw stockMismatch();
        InventoryBalance after = new InventoryBalance(tenant, warehouse, a.sellerSku(), before.onHand(),
                before.reserved() - quantity, before.inTransit(), Math.addExact(before.frozen(), quantity), now);
        inventory.saveBalance(after);
        movement(tenant, warehouse, a.sellerSku(), a.batchNo(), a.locationId(), command.shortPickId(), operator,
                InventoryTransaction.TransactionType.SHORT_PICK_REPORT, quantity, before, after, physical, physical,
                0, quantity, "缺拣转待复核：" + reason);
        return get(tenant, command.shortPickId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BatchStock> candidates(String tenant, String id) {
        ShortPickCase c = get(tenant, id);
        if (!c.allowBatchSubstitution() || c.replacedAt() != null) return List.of();
        return picks.candidates(tenant, c.warehouseId(), c.sellerSku()).stream()
                .filter(b -> b.locationId().equals(c.locationId()) && !b.batchNo().equals(c.batchNo()))
                .filter(b -> b.quantity() >= c.shortQuantity()).toList();
    }

    @Transactional
    public ShortPickCase replace(String tenant, String id, String operator, Replacement command) {
        ShortPickCase c = lockCase(tenant, id);
        String location = command.locationCode().strip();
        if (c.replacedAt() != null) {
            if (command.requestId().equals(c.replacementRequestId()) && command.batchNo().equals(c.replacementBatchNo())
                    && location.equals(c.locationCode())) return c;
            throw conflict();
        }
        requireActive(tenant, c.outboundId());
        if (!c.allowBatchSubstitution()) throw new BizException("SHORT_PICK_SUBSTITUTION_FORBIDDEN", "订单下发时未允许换批");
        if (!location.equals(c.locationCode()) || command.batchNo().equals(c.batchNo())) {
            throw new BizException("SHORT_PICK_REPLACEMENT_INVALID", "只能使用同库位、同 SKU 的其他良品批次");
        }
        lockLocation(tenant, c.warehouseId(), location, c.locationId(), true);
        InventoryBalance before = balance(tenant, c.warehouseId(), c.sellerSku());
        BatchStock target = picks.candidates(tenant, c.warehouseId(), c.sellerSku()).stream()
                .filter(b -> b.locationId().equals(c.locationId()) && b.batchNo().equals(command.batchNo()))
                .filter(b -> b.quantity() >= c.shortQuantity()).findFirst().orElseThrow(this::unavailable);
        int quantity = c.shortQuantity();
        if (before.getAvailable() < quantity || picks.reserve(tenant, target, quantity) != 1) throw unavailable();
        Allocation original = picks.allocations(tenant, c.outboundId()).stream().filter(a -> a.allocationId().equals(c.allocationId()))
                .findFirst().orElseThrow();
        String allocationId = UUID.randomUUID().toString();
        picks.insertAllocation(tenant, new Allocation(allocationId, c.outboundId(), original.lineId(), original.salesLineId(), c.sellerSku(),
                target.locationId(), target.locationCode(), target.batchNo(), target.receiptId(), target.purchaseOrderId(), quantity, 0));
        try { reviews.replace(tenant, id, command.requestId(), allocationId, operator, Instant.now()); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        InventoryBalance after = new InventoryBalance(tenant, c.warehouseId(), c.sellerSku(), before.onHand(),
                before.reserved() + quantity, before.inTransit(), before.frozen(), Instant.now());
        inventory.saveBalance(after);
        int physical = reviews.physical(tenant, target.batchNo(), target.locationId());
        movement(tenant, c.warehouseId(), c.sellerSku(), target.batchNo(), target.locationId(), id, operator,
                InventoryTransaction.TransactionType.SHORT_PICK_REPLACE, quantity, before, after, physical, physical,
                -quantity, 0, "缺拣补占替代批次，原批次 " + c.batchNo());
        return get(tenant, id);
    }

    @Transactional
    public ShortPickCase count(String tenant, String id, String operator, int found, int missing, String remark) {
        ShortPickCase c = lockCase(tenant, id);
        validateCount(c, found, missing);
        remark = remark == null ? null : remark.strip();
        if (Objects.equals(c.foundQuantity(), found) && Objects.equals(c.missingQuantity(), missing)
                && Objects.equals(c.countRemark(), remark)) return c;
        if (c.confirmedAt() != null) throw new BizException("STOCK_REVIEW_CONFIRMED", "主管已确认，不能修改复盘数量");
        reviews.count(tenant, id, found, missing, remark, operator, Instant.now());
        return get(tenant, id);
    }

    @Transactional
    public ShortPickCase confirm(String tenant, String id, String operator, int found, int missing) {
        ShortPickCase c = lockCase(tenant, id);
        if (c.countedAt() == null) throw new BizException("STOCK_REVIEW_COUNT_REQUIRED", "请先由仓管录入复盘结果");
        if (!Objects.equals(c.foundQuantity(), found) || !Objects.equals(c.missingQuantity(), missing)) {
            throw new BizException("STOCK_REVIEW_COUNT_CHANGED", "复盘数量已变化，请重新核对后确认");
        }
        if (c.confirmedAt() != null) return c;
        lockLocation(tenant, c.warehouseId(), c.locationCode(), c.locationId(), false);
        InventoryBalance before = balance(tenant, c.warehouseId(), c.sellerSku());
        int physical = reviews.physical(tenant, c.batchNo(), c.locationId());
        if (before.frozen() < c.shortQuantity() || before.onHand() < missing || reviews.resolveStock(tenant, c) != 1) throw stockMismatch();
        InventoryBalance after = new InventoryBalance(tenant, c.warehouseId(), c.sellerSku(), before.onHand() - missing,
                before.reserved(), before.inTransit(), before.frozen() - c.shortQuantity(), Instant.now());
        inventory.saveBalance(after);
        reviews.confirm(tenant, id, operator, Instant.now());
        movement(tenant, c.warehouseId(), c.sellerSku(), c.batchNo(), c.locationId(), id, operator,
                InventoryTransaction.TransactionType.STOCK_REVIEW, c.shortQuantity(), before, after, physical, physical - missing,
                found, -c.shortQuantity(), "主管确认复盘：找回 " + found + "，短少 " + missing);
        return get(tenant, id);
    }

    private ShortPickCase lockCase(String tenant, String id) {
        String outbound = reviews.outbound(tenant, id);
        if (outbound == null) throw new BizException("SHORT_PICK_NOT_FOUND", "缺拣复核记录不存在");
        lockWork(tenant, outbound);
        return get(tenant, id);
    }
    private void lockWork(String tenant, String outbound) {
        if (picks.lockWork(tenant, outbound) == null) throw new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在");
    }
    private void requireActive(String tenant, String outbound) {
        var order = orders.findOutboundOrder(tenant, outbound).orElseThrow();
        if (order.status() != OutboundOrderStatus.PENDING && order.status() != OutboundOrderStatus.PICKING) {
            throw new BizException("SHORT_PICK_STATUS_INVALID", "当前出库单不允许登记缺拣或换批");
        }
    }
    private void lockLocation(String tenant, String warehouse, String code, String id, boolean requireEnabled) {
        var locations = inventory.lockLocationsByCode(tenant, warehouse, code);
        if (locations.size() != 1 || !locations.get(0).locationId().equals(id)
                || locations.get(0).purpose() != WarehouseLocation.Purpose.GOOD || (requireEnabled && !locations.get(0).enabled())) {
            throw new BizException("SHORT_PICK_LOCATION_INVALID", "请使用原仓库中对应的良品库位");
        }
    }
    private InventoryBalance balance(String tenant, String warehouse, String sku) {
        return inventory.lockBalance(tenant, warehouse, sku).orElseThrow(this::stockMismatch);
    }
    private void validateCount(ShortPickCase c, int found, int missing) {
        if (found < 0 || missing < 0 || (long) found + missing != c.shortQuantity()) {
            throw new BizException("STOCK_REVIEW_QUANTITY_INVALID", "找回与短少数量之和须等于原缺拣数量");
        }
    }
    private void movement(String tenant, String warehouse, String sku, String batch, String location, String id, String operator,
            InventoryTransaction.TransactionType type, int quantity, InventoryBalance before, InventoryBalance after,
            int physicalBefore, int physicalAfter, int availableDelta, int reviewDelta, String remark) {
        inventory.saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenant, warehouse, sku, type, quantity,
                before.onHand(), before.reserved(), after.onHand(), after.reserved(), "SHORT_PICK", id, remark, Instant.now(), batch,
                operator, 0, 0, 0, location, null, physicalBefore, physicalAfter, null, null, availableDelta, reviewDelta));
    }
    private BizException unavailable() { return new BizException("SHORT_PICK_REPLACEMENT_UNAVAILABLE", "替代批次无足够可用量，或不属于同库位同 SKU"); }
    private BizException conflict() { return new BizException("SHORT_PICK_REQUEST_CONFLICT", "该作业编号已用于其他缺拣或换批内容"); }
    private BizException stockMismatch() { return new BizException("SHORT_PICK_STOCK_MISMATCH", "缺拣、预占与待复核库存不一致，请核查"); }
    public record Report(String shortPickId, String allocationId, int quantity, String reason) {}
    public record Replacement(String requestId, String locationCode, String batchNo) {}
}
