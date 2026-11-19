package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.SalesOrderPicking;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.OutboundOrder;
import com.aidotnet.erp.wms.domain.OutboundOrderLine;
import com.aidotnet.erp.wms.domain.OutboundOrderStatus;
import com.aidotnet.erp.wms.domain.WarehouseLocation;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class SalesPickingService implements SalesOrderPicking {
    private final SalesPickingMapper picks;
    private final InventoryStore inventory;
    private final BatchQualityGuard quality;
    private final WmsOrderStore orders;

    public SalesPickingService(SalesPickingMapper picks, InventoryStore inventory, WmsOrderStore orders, BatchQualityGuard quality) {
        this.picks = picks;
        this.inventory = inventory;
        this.quality = quality;
        this.orders = orders;
    }

    @Override
    public boolean exists(String tenantId, String salesOrderId) {
        return picks.findBySalesOrder(tenantId, salesOrderId) != null;
    }

    @Override
    @Transactional
    public Work dispatch(String tenantId, String salesOrderId, String warehouseId, String operatorId, List<Line> lines,
                         boolean allowBatchSubstitution) {
        String existing = picks.findBySalesOrder(tenantId, salesOrderId);
        if (existing != null) {
            Work work = get(tenantId, existing);
            if (!warehouseId.equals(work.warehouseId()) || work.allowBatchSubstitution() != allowBatchSubstitution) {
                throw new BizException("SALES_DISPATCH_CONFLICT", "订单已下发，不能修改仓库或换批约定");
            }
            return work;
        }
        if (inventory.findWarehouse(tenantId, warehouseId).isEmpty()) {
            throw new BizException("WAREHOUSE_NOT_FOUND", "仓库不存在");
        }
        // 按 SKU 固定顺序锁定总库存；上架和旧库存操作也使用同一余额锁。
        Map<String, InventoryBalance> balances = new LinkedHashMap<>();
        for (String sku : lines.stream().map(Line::sellerSku).distinct().sorted().toList()) {
            balances.put(sku, inventory.lockBalance(tenantId, warehouseId, sku)
                    .orElseThrow(() -> insufficient(sku)));
        }
        String outboundId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        orders.saveOutboundOrder(new OutboundOrder(outboundId, tenantId, warehouseId, "SALES_BATCH_PICK",
                salesOrderId, OutboundOrderStatus.PENDING, "销售订单批次拣货", now, now));
        picks.insertWork(tenantId, outboundId, salesOrderId, operatorId, allowBatchSubstitution);
        for (Line line : lines.stream().sorted(Comparator.comparing(Line::sellerSku).thenComparing(Line::lineId)).toList()) {
            String lineId = UUID.randomUUID().toString();
            orders.saveOutboundOrderLine(new OutboundOrderLine(lineId, outboundId, line.sellerSku(), null,
                    line.quantity(), 0, null));
            int remaining = line.quantity();
            InventoryBalance before = balances.get(line.sellerSku());
            if (before.getAvailable() < remaining) throw insufficient(line.sellerSku());
            for (BatchStock stock : picks.candidates(tenantId, warehouseId, line.sellerSku())) {
                int quantity = Math.min(remaining, stock.quantity());
                if (picks.reserve(tenantId, stock, quantity) != 1) throw insufficient(line.sellerSku());
                Allocation allocation = new Allocation(UUID.randomUUID().toString(), outboundId, lineId, line.lineId(),
                        line.sellerSku(), stock.locationId(), stock.locationCode(), stock.batchNo(), stock.receiptId(),
                        stock.purchaseOrderId(), quantity, 0);
                picks.insertAllocation(tenantId, allocation);
                InventoryBalance after = new InventoryBalance(tenantId, warehouseId, line.sellerSku(), before.onHand(),
                        Math.addExact(before.reserved(), quantity), before.inTransit(), before.frozen(), now);
                inventory.saveBalance(after);
                inventory.saveTransaction(transaction(tenantId, warehouseId, allocation, operatorId,
                        InventoryTransaction.TransactionType.RESERVE, quantity, before, after.reserved(), outboundId,
                        "销售订单预占", null, null, -quantity));
                before = after;
                remaining -= quantity;
                if (remaining == 0) break;
            }
            if (remaining > 0) throw insufficient(line.sellerSku());
            balances.put(line.sellerSku(), before);
        }
        return get(tenantId, outboundId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Work> list(String tenantId, String salesOrderId) {
        return picks.list(tenantId, salesOrderId).stream().map(id -> get(tenantId, id)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Work get(String tenantId, String outboundId) {
        OutboundOrder order = orders.findOutboundOrder(tenantId, outboundId)
                .filter(o -> "SALES_BATCH_PICK".equals(o.referenceType()))
                .orElseThrow(() -> new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在"));
        List<Allocation> allocations = picks.allocations(tenantId, outboundId);
        var orderLines = orders.listOutboundOrderLines(outboundId);
        int required = orderLines.stream().mapToInt(l -> l.requiredQuantity()).sum();
        boolean pickedAll = orderLines.stream().allMatch(l -> l.pickedQuantity() == l.requiredQuantity());
        String status = pickedAll ? "PICKED"
                : allocations.stream().anyMatch(a -> a.pickedQuantity() > 0) ? "PICKING" : "RESERVED";
        if (allocations.stream().mapToInt(a -> a.plannedQuantity() - a.shortQuantity() - a.cancelledQuantity()).sum() < required) status = "SHORT_PICK";
        if (status.equals("SHORT_PICK") && allocations.stream().anyMatch(a -> a.cancelledQuantity() > 0)) status = "NEEDS_ALLOCATION";
        if (order.status() == OutboundOrderStatus.SHIPPED) status = "SHIPPED";
        else if (!status.equals("NEEDS_ALLOCATION") && orders.listOutboundPackages(tenantId, outboundId).stream()
                .anyMatch(p -> p.status() == com.aidotnet.erp.wms.domain.OutboundPackageStatus.SHIPPED)) status = "PARTIALLY_SHIPPED";
        if (quality.heldOutbound(tenantId, outboundId)) status = "QUALITY_HOLD";
        return new Work(outboundId, order.referenceId(), order.warehouseId(), status,
                required, picks.allowBatchSubstitution(tenantId, outboundId),
                allocations, picks.picks(tenantId, outboundId));
    }

    @Transactional
    public ActualPick confirm(String tenantId, String outboundId, String operatorId, PickCommand command) {
        if (picks.lockWork(tenantId, outboundId) == null) {
            throw new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在");
        }
        ActualPick existing = picks.findPick(tenantId, command.requestId());
        if (existing != null) {
            if (existing.outboundId().equals(outboundId) && existing.allocationId().equals(command.allocationId())
                    && existing.locationCode().equals(command.locationCode().strip())
                    && existing.batchNo().equals(command.batchNo()) && existing.quantity() == command.quantity()) return existing;
            throw new BizException("SALES_PICK_REQUEST_CONFLICT", "该操作编号已用于另一笔实拣");
        }
        Work work = get(tenantId, outboundId);
        OutboundOrder order = orders.findOutboundOrder(tenantId, outboundId).orElseThrow();
        if (order.status() != OutboundOrderStatus.PENDING && order.status() != OutboundOrderStatus.PICKING) {
            throw new BizException("SALES_PICK_STATUS_INVALID", "当前出库单不允许拣货");
        }
        Allocation a = work.allocations().stream().filter(v -> v.allocationId().equals(command.allocationId()))
                .findFirst().orElseThrow(() -> new BizException("SALES_PICK_ALLOCATION_INVALID", "分配明细不属于当前出库单"));
        List<WarehouseLocation> locations = inventory.lockLocationsByCode(tenantId, work.warehouseId(), command.locationCode().strip());
        if (locations.size() != 1 || !locations.get(0).enabled() || locations.get(0).purpose() != WarehouseLocation.Purpose.GOOD
                || !locations.get(0).locationId().equals(a.locationId()) || !command.batchNo().equals(a.batchNo())) {
            throw new BizException("SALES_PICK_SCAN_INVALID", "库位或批次不符，请扫描计划明细对应的良品实物标签");
        }
        if (command.quantity() <= 0 || command.quantity() > a.plannedQuantity() - a.pickedQuantity() - a.shortQuantity() - a.cancelledQuantity()) {
            throw new BizException("SALES_PICK_QUANTITY_INVALID", "实拣数量必须大于零且不能超过当前明细待拣数量");
        }
        InventoryBalance balance = inventory.lockBalance(tenantId, work.warehouseId(), a.sellerSku()).orElseThrow();
        quality.requireClear(tenantId, a.batchNo());
        int beforeLocation = picks.physicalQuantity(tenantId, a);
        if (picks.pick(tenantId, a, command.quantity()) != 1) {
            throw new BizException("SALES_PICK_STOCK_INVALID", "批次预占库存不足，请核查库存");
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ActualPick actual = new ActualPick(command.requestId(), outboundId, a.allocationId(), a.locationId(),
                a.locationCode(), a.batchNo(), a.sellerSku(), a.receiptId(), a.purchaseOrderId(), command.quantity(), operatorId, now);
        picks.insertPick(tenantId, actual);
        picks.pickAllocation(tenantId, a.allocationId(), command.quantity());
        int linePicked = work.allocations().stream().filter(v -> v.lineId().equals(a.lineId())).mapToInt(Allocation::pickedQuantity).sum();
        OutboundOrderLine line = orders.listOutboundOrderLines(outboundId).stream().filter(v -> v.lineId().equals(a.lineId())).findFirst().orElseThrow();
        orders.updateOutboundOrderLine(new OutboundOrderLine(line.lineId(), outboundId, line.sellerSku(), null,
                line.requiredQuantity(), linePicked + command.quantity(), null));
        orders.saveOutboundOrder(new OutboundOrder(outboundId, tenantId, work.warehouseId(), order.referenceType(),
                order.referenceId(), OutboundOrderStatus.PICKING, order.remark(), order.createdAt(), now));
        inventory.saveTransaction(transaction(tenantId, work.warehouseId(), a, operatorId,
                InventoryTransaction.TransactionType.SALES_PICK, command.quantity(), balance, balance.reserved(),
                outboundId, "实拣转发货暂存区，操作编号 " + command.requestId(), beforeLocation,
                beforeLocation - command.quantity(), 0));
        return actual;
    }

    @Transactional
    public Work reallocate(String tenant, String outbound, String operator) {
        if (picks.lockWork(tenant, outbound) == null) throw new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在");
        Work work = get(tenant, outbound);
        Map<String, InventoryBalance> balances = new LinkedHashMap<>();
        var lines = orders.listOutboundOrderLines(outbound).stream()
                .sorted(Comparator.comparing(OutboundOrderLine::sellerSku).thenComparing(OutboundOrderLine::lineId)).toList();
        for (var line : lines) {
            int remaining = picks.qualityReallocationQuantity(tenant, outbound, line.lineId());
            if (remaining <= 0) continue;
            InventoryBalance before = balances.computeIfAbsent(line.sellerSku(), sku -> inventory.lockBalance(tenant, work.warehouseId(), sku)
                    .orElseThrow(() -> insufficient(sku)));
            var original = work.allocations().stream().filter(a -> a.lineId().equals(line.lineId())).findFirst().orElseThrow();
            if (before.getAvailable() < remaining) throw insufficient(line.sellerSku());
            for (BatchStock stock : picks.candidates(tenant, work.warehouseId(), line.sellerSku())) {
                int quantity = Math.min(remaining, stock.quantity());
                if (picks.reserve(tenant, stock, quantity) != 1) throw insufficient(line.sellerSku());
                Allocation a = new Allocation(UUID.randomUUID().toString(), outbound, line.lineId(), original.salesLineId(), line.sellerSku(),
                        stock.locationId(), stock.locationCode(), stock.batchNo(), stock.receiptId(), stock.purchaseOrderId(), quantity, 0);
                picks.insertAllocation(tenant, a);
                picks.markQualityReplacement(tenant, a.allocationId());
                InventoryBalance after = new InventoryBalance(tenant, work.warehouseId(), line.sellerSku(), before.onHand(),
                        before.reserved() + quantity, before.inTransit(), before.frozen(), Instant.now());
                inventory.saveBalance(after);
                inventory.saveTransaction(transaction(tenant, work.warehouseId(), a, operator, InventoryTransaction.TransactionType.RESERVE,
                        quantity, before, after.reserved(), outbound, "质检更正后重新分配合格批次", null, null, -quantity));
                before = after;
                remaining -= quantity;
                if (remaining == 0) break;
            }
            if (remaining > 0) throw insufficient(line.sellerSku());
            balances.put(line.sellerSku(), before);
        }
        return get(tenant, outbound);
    }

    private InventoryTransaction transaction(String tenant, String warehouse, Allocation a, String operator,
            InventoryTransaction.TransactionType type, int quantity, InventoryBalance before, int afterReserved,
            String outboundId, String remark, Integer beforeLocation, Integer afterLocation, int availableDelta) {
        return new InventoryTransaction(UUID.randomUUID().toString(), tenant, warehouse, a.sellerSku(), type,
                quantity, before.onHand(), before.reserved(), before.onHand(), afterReserved, "SALES_BATCH_PICK",
                outboundId, remark, Instant.now(), a.batchNo(), operator, 0, 0, 0,
                a.locationId(), null, beforeLocation, afterLocation, null, null, availableDelta);
    }
    private BizException insufficient(String sku) {
        return new BizException("SALES_PICK_INSUFFICIENT_STOCK", "已上架可用良品不足，SKU " + sku);
    }
    public record PickCommand(String requestId, String allocationId, String locationCode, String batchNo, int quantity) {}
}
