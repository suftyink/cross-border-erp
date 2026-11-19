package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.fba.FbaBoxLabels;
import com.aidotnet.erp.common.fba.FbaWarehouseShipments;
import com.aidotnet.erp.wms.domain.InventoryTransaction;
import com.aidotnet.erp.wms.domain.OutboundOrder;
import com.aidotnet.erp.wms.domain.OutboundOrderStatus;
import com.aidotnet.erp.wms.domain.OutboundPackage;
import com.aidotnet.erp.wms.domain.OutboundPackageStatus;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaCartonMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaDispatchMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaDispatchMapper.DispatchRecord;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FbaDispatchService implements FbaWarehouseShipments {
    private final SalesPickingMapper locks;
    private final SalesPickingService picking;
    private final FbaCartonService cartons;
    private final FbaBoxLabels labels;
    private final WmsOrderStore orders;
    private final ShipmentStockPosting posting;
    private final FbaDispatchMapper dispatch;
    public FbaDispatchService(SalesPickingMapper locks, SalesPickingService picking, FbaCartonService cartons,
            FbaBoxLabels labels, WmsOrderStore orders, ShipmentStockPosting posting, FbaDispatchMapper dispatch) {
        this.locks = locks; this.picking = picking; this.cartons = cartons; this.labels = labels;
        this.orders = orders; this.posting = posting; this.dispatch = dispatch;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preparation prepare(String tenant, String outbound) {
        var work = picking.get(tenant, outbound);
        if (!work.sourceType().equals("FBA_SHIPMENT")) throw new BizException("FBA_PICK_NOT_FOUND", "当前作业不是 FBA 备货");
        var plan = labels.find(tenant, work.sourceId());
        var boxes = cartons.list(tenant, new FbaCartonMapper.Filter(outbound, null, null, null)).stream().filter(c -> !c.status().equals("CANCELLED")).toList();
        List<String> missing = boxes.stream().filter(c -> !c.bindingActive() || plan == null || !plan.boxNumbers().contains(c.fbaBoxNumber())).map(FbaCartonService.Carton::cartonCode).toList();
        Map<String, Integer> packed = new HashMap<>();
        boxes.forEach(c -> c.lines().forEach(l -> packed.merge(l.pickId(), l.quantity(), Math::addExact)));
        var actual = work.picks().stream().filter(p -> p.quantity() > p.returnedQuantity()).collect(Collectors.toMap(p -> p.requestId(), p -> p.quantity() - p.returnedQuantity()));
        int planned = plan == null ? 0 : plan.boxNumbers().size();
        boolean ready = planned > 0 && planned == boxes.size() && missing.isEmpty() && work.status().equals("PICKED") && packed.equals(actual)
                && packed.values().stream().mapToLong(Integer::longValue).sum() == work.requiredQuantity();
        return new Preparation(work.sourceId(), work.status(), work.requiredQuantity(), planned, boxes.size(),
                packed.values().stream().mapToInt(Integer::intValue).sum(), missing, ready, dispatch.find(tenant, outbound));
    }

    @Transactional
    public DispatchRecord ship(String tenant, String outbound, String operator, String carrier, String tracking) {
        if (locks.lockWork(tenant, outbound) == null) throw new BizException("FBA_PICK_NOT_FOUND", "FBA 拣货作业不存在");
        carrier = carrier.strip(); tracking = tracking.strip();
        DispatchRecord previous = dispatch.find(tenant, outbound);
        if (previous != null) {
            if (!previous.carrierCode().equals(carrier) || !previous.trackingNo().equals(tracking)) throw new BizException("FBA_ALREADY_SHIPPED", "整单已出库，不能用其他运单重复确认");
            return previous;
        }
        Preparation preparation = prepare(tenant, outbound);
        if (!preparation.missingLabelCartonCodes().isEmpty()) throw new BizException("FBA_LABELS_REQUIRED", "以下内部箱尚未完成贴标绑定：" + String.join("、", preparation.missingLabelCartonCodes()));
        if (!preparation.readyToShip()) throw new BizException("FBA_DISPATCH_NOT_READY", "须登记完整箱号清单、拣齐并完成全部装箱和贴标，质检暂停时不能出库");
        var boxes = cartons.list(tenant, new FbaCartonMapper.Filter(outbound, null, null, null)).stream().filter(c -> !c.status().equals("CANCELLED")).toList();
        OutboundOrder work = orders.findOutboundOrder(tenant, outbound).orElseThrow();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var details = boxes.stream().flatMap(c -> c.lines().stream().map(l -> new ShipmentStockPosting.Detail(l,
                "FBA 整单出库，内部箱 " + c.cartonCode() + "，FBA 箱 " + c.fbaBoxNumber() + "，原库位 " + l.locationCode()))).toList();
        posting.post(tenant, work.warehouseId(), details, new ShipmentStockPosting.Reference(InventoryTransaction.TransactionType.FBA_SHIP, "FBA_SHIPMENT", outbound), operator, now);
        for (var box : boxes) {
            OutboundPackage p = orders.findOutboundPackage(tenant, box.cartonId()).orElseThrow();
            orders.saveOutboundPackage(new OutboundPackage(p.packageId(), tenant, outbound, p.warehouseId(), carrier, tracking,
                    p.weightKg(), OutboundPackageStatus.SHIPPED, p.remark(), p.packedAt(), now, p.createdAt(), now));
        }
        orders.saveOutboundOrder(new OutboundOrder(outbound, tenant, work.warehouseId(), work.referenceType(), work.referenceId(),
                OutboundOrderStatus.SHIPPED, work.remark(), work.createdAt(), now));
        var plan = labels.find(tenant, work.referenceId());
        DispatchRecord record = new DispatchRecord(outbound, work.referenceId(), work.warehouseId(), plan.amazonShipmentId(),
                preparation.requiredQuantity(), boxes.size(), carrier, tracking, operator, now, "SHIPPED", "PENDING", null);
        dispatch.insert(tenant, record);
        return record;
    }

    @Override
    public Shipment get(String tenant, String outbound) { return record(tenant, outbound).proof(); }
    public DispatchRecord record(String tenant, String outbound) {
        var record = dispatch.find(tenant, outbound);
        if (record == null) throw new BizException("FBA_SHIPMENT_NOT_DISPATCHED", "整单尚未实际出库");
        return record;
    }
    public record Preparation(String fbaShipmentId, String workStatus, int requiredQuantity, int plannedCartonCount,
                              int packedCartonCount, int packedQuantity, List<String> missingLabelCartonCodes, boolean readyToShip, DispatchRecord shipment) {}
}
