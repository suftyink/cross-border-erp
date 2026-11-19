package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.wms.domain.InventoryBalance;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.OutboundOrder;
import com.aidotnet.erp.wms.domain.OutboundOrderStatus;
import com.aidotnet.erp.wms.domain.OutboundPackage;
import com.aidotnet.erp.wms.domain.OutboundPackageLine;
import com.aidotnet.erp.wms.domain.OutboundPackageStatus;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesDispatchService implements WarehouseShipments {
    private final SalesDispatchMapper dispatch;
    private final SalesPickingMapper picks;
    private final WmsOrderStore orders;
    private final InventoryStore inventory;

    public SalesDispatchService(SalesDispatchMapper dispatch, SalesPickingMapper picks, WmsOrderStore orders, InventoryStore inventory) {
        this.dispatch = dispatch;
        this.picks = picks;
        this.orders = orders;
        this.inventory = inventory;
    }

    @Transactional
    public Shipment pack(String tenant, String outboundId, String packageId, List<PackLine> lines) {
        OutboundOrder work = lockWork(tenant, outboundId);
        Map<String, Integer> requested = new HashMap<>();
        for (PackLine line : lines) {
            if (line.quantity() <= 0 || requested.putIfAbsent(line.pickId(), line.quantity()) != null) {
                throw new BizException("SALES_PACKAGE_LINES_INVALID", "实拣记录不能重复，装入件数须大于零");
            }
        }
        var existing = orders.findOutboundPackage(tenant, packageId);
        if (existing.isPresent()) {
            Map<String, Integer> stored = new HashMap<>();
            dispatch.lines(tenant, packageId).forEach(l -> stored.put(l.pickId(), l.quantity()));
            if (!existing.get().orderId().equals(outboundId) || !stored.equals(requested)) {
                throw new BizException("SALES_PACKAGE_REQUEST_CONFLICT", "该包裹编号已用于其他装箱明细");
            }
            return get(tenant, packageId);
        }
        if (work.status() != OutboundOrderStatus.PICKING || lines.isEmpty()) {
            throw new BizException("SALES_PACKAGE_STATUS_INVALID", "须先完成相应实拣，才能创建包裹");
        }
        var actual = picks.picks(tenant, outboundId);
        Instant now = Instant.now();
        orders.saveOutboundPackage(new OutboundPackage(packageId, tenant, outboundId, work.warehouseId(),
                null, null, null, OutboundPackageStatus.PACKED, "销售实拣装箱", now, null, now, now));
        var allocations = picks.allocations(tenant, outboundId);
        for (PackLine line : lines) {
            var pick = actual.stream().filter(p -> p.requestId().equals(line.pickId())).findFirst()
                    .orElseThrow(() -> new BizException("SALES_PACKAGE_PICK_INVALID", "实拣记录不属于当前出库单"));
            if (line.quantity() > pick.quantity() - dispatch.packedQuantity(tenant, pick.requestId())) {
                throw new BizException("SALES_PACKAGE_EXCEEDS_PICKED", "装入数量超过该实拣记录尚未装箱的数量");
            }
            var allocation = allocations.stream().filter(a -> a.allocationId().equals(pick.allocationId())).findFirst().orElseThrow();
            dispatch.insertPick(tenant, packageId, pick.requestId(), line.quantity());
            orders.saveOutboundPackageLine(new OutboundPackageLine(UUID.randomUUID().toString(), packageId,
                    allocation.lineId(), pick.sellerSku(), line.quantity(), pick.batchNo()));
        }
        return get(tenant, packageId);
    }

    @Transactional
    public Shipment weigh(String tenant, String packageId, BigDecimal weight) {
        OutboundPackage p = lockPackage(tenant, packageId);
        if (p.status() == OutboundPackageStatus.SHIPPED) {
            if (p.weightKg().compareTo(weight) != 0) throw new BizException("SALES_PACKAGE_ALREADY_SHIPPED", "已发货包裹不能修改重量");
            return get(tenant, packageId);
        }
        orders.saveOutboundPackage(copy(p, OutboundPackageStatus.WEIGHED, weight, null, null, null));
        return get(tenant, packageId);
    }

    @Transactional
    public Shipment ship(String tenant, String packageId, String operator, String carrierCode, String trackingNo) {
        OutboundPackage p = lockPackage(tenant, packageId);
        carrierCode = carrierCode.strip();
        trackingNo = trackingNo.strip();
        if (p.status() == OutboundPackageStatus.SHIPPED) {
            if (!carrierCode.equals(p.carrierCode()) || !trackingNo.equals(p.trackingNo())) {
                throw new BizException("SALES_PACKAGE_ALREADY_SHIPPED", "包裹已发货，不能用新承运或运单信息重复确认");
            }
            return get(tenant, packageId);
        }
        if (p.status() != OutboundPackageStatus.WEIGHED || p.weightKg() == null || p.weightKg().signum() <= 0) {
            throw new BizException("SALES_PACKAGE_WEIGHT_REQUIRED", "包裹须先完成称重");
        }
        OutboundOrder work = orders.findOutboundOrder(tenant, p.orderId()).orElseThrow();
        if (work.status() != OutboundOrderStatus.PICKING) throw new BizException("SALES_PACKAGE_STATUS_INVALID", "当前出库单不允许发货");
        List<Line> lines = dispatch.lines(tenant, packageId);
        if (lines.isEmpty()) throw new BizException("SALES_PACKAGE_LINES_INVALID", "包裹没有实拣明细");
        Map<String, InventoryBalance> balances = new LinkedHashMap<>();
        for (String sku : lines.stream().map(Line::sellerSku).distinct().sorted().toList()) {
            balances.put(sku, inventory.lockBalance(tenant, p.warehouseId(), sku)
                    .orElseThrow(() -> new BizException("INVENTORY_NOT_FOUND", "库存不存在")));
        }
        Instant now = Instant.now();
        for (Line line : lines) {
            InventoryBalance before = balances.get(line.sellerSku());
            int quantity = line.quantity();
            int physical = dispatch.physicalQuantity(tenant, line);
            if (before.reserved() < quantity || before.onHand() < quantity || dispatch.deduct(tenant, line) != 1) {
                throw new BizException("SALES_SHIPMENT_STOCK_INVALID", "实拣暂存或预占库存不足，请核查库存");
            }
            InventoryBalance after = new InventoryBalance(tenant, p.warehouseId(), line.sellerSku(),
                    before.onHand() - quantity, before.reserved() - quantity, before.inTransit(), before.frozen(), now);
            inventory.saveBalance(after);
            inventory.saveTransaction(new InventoryTransaction(UUID.randomUUID().toString(), tenant, p.warehouseId(),
                    line.sellerSku(), InventoryTransaction.TransactionType.SALES_SHIP, quantity, before.onHand(), before.reserved(),
                    after.onHand(), after.reserved(), "SALES_SHIPMENT", packageId, "发货暂存出库，原库位 " + line.locationCode(), now,
                    line.batchNo(), operator, 0, 0, 0, line.locationId(), null, physical, physical, null, null, 0));
            balances.put(line.sellerSku(), after);
        }
        orders.saveOutboundPackage(copy(p, OutboundPackageStatus.SHIPPED, p.weightKg(), carrierCode, trackingNo, now));
        dispatch.insertShipment(tenant, packageId, p.orderId(), operator, now);
        int required = orders.listOutboundOrderLines(p.orderId()).stream().mapToInt(l -> l.requiredQuantity()).sum();
        if (dispatch.shippedQuantity(tenant, p.orderId()) == required) {
            orders.saveOutboundOrder(new OutboundOrder(work.orderId(), tenant, work.warehouseId(), work.referenceType(),
                    work.referenceId(), OutboundOrderStatus.SHIPPED, work.remark(), work.createdAt(), now));
        }
        return get(tenant, packageId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Shipment get(String tenant, String packageId) {
        OutboundPackage p = orders.findOutboundPackage(tenant, packageId)
                .orElseThrow(() -> new BizException("SALES_PACKAGE_NOT_FOUND", "销售包裹不存在"));
        OutboundOrder work = orders.findOutboundOrder(tenant, p.orderId()).filter(o -> "SALES_BATCH_PICK".equals(o.referenceType()))
                .orElseThrow(() -> new BizException("SALES_PACKAGE_NOT_FOUND", "销售包裹不存在"));
        var state = dispatch.state(tenant, packageId);
        return new Shipment(packageId, p.orderId(), work.referenceId(), p.warehouseId(), p.status().name(),
                p.weightKg(), p.carrierCode(), p.trackingNo(), p.shippedAt(), state == null ? null : state.operatorId(),
                state == null ? "NOT_SHIPPED" : state.omsSyncStatus(), state == null ? null : state.syncError(), dispatch.lines(tenant, packageId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Shipment> forOutbound(String tenant, String outboundId) {
        requireWork(tenant, outboundId);
        return orders.listOutboundPackages(tenant, outboundId).stream().map(p -> get(tenant, p.packageId())).toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Shipment> forOrder(String tenant, String orderId) {
        return dispatch.packagesForOrder(tenant, orderId).stream().map(id -> get(tenant, id)).toList();
    }

    public List<Trace> trace(String tenant, SalesDispatchMapper.TraceFilter filter) { return dispatch.trace(tenant, filter); }

    private OutboundOrder requireWork(String tenant, String outbound) {
        return orders.findOutboundOrder(tenant, outbound).filter(o -> "SALES_BATCH_PICK".equals(o.referenceType()))
                .orElseThrow(() -> new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在"));
    }
    private OutboundOrder lockWork(String tenant, String outbound) {
        if (picks.lockWork(tenant, outbound) == null) throw new BizException("SALES_PICK_NOT_FOUND", "销售拣货作业不存在");
        return requireWork(tenant, outbound);
    }
    private OutboundPackage lockPackage(String tenant, String packageId) {
        String outbound = dispatch.packageOutbound(tenant, packageId);
        if (outbound == null) throw new BizException("SALES_PACKAGE_NOT_FOUND", "销售包裹不存在");
        // 锁定作业后才读取包裹，避免锁等待前的包裹对象留在 MyBatis 会话缓存中。
        lockWork(tenant, outbound);
        return orders.findOutboundPackage(tenant, packageId).orElseThrow();
    }
    private OutboundPackage copy(OutboundPackage p, OutboundPackageStatus status, BigDecimal weight,
                                 String carrier, String tracking, Instant shipped) {
        return new OutboundPackage(p.packageId(), p.tenantId(), p.orderId(), p.warehouseId(), carrier, tracking,
                weight, status, p.remark(), p.packedAt(), shipped, p.createdAt(), Instant.now());
    }
    public record PackLine(String pickId, int quantity) {}
}
