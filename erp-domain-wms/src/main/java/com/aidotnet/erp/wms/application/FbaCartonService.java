package com.aidotnet.erp.wms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.wms.domain.OutboundPackage;
import com.aidotnet.erp.wms.domain.OutboundPackageLine;
import com.aidotnet.erp.wms.domain.OutboundPackageStatus;
import com.aidotnet.erp.wms.infrastructure.InventoryStore;
import com.aidotnet.erp.wms.infrastructure.WmsOrderStore;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaCartonMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaCartonMapper.CartonRecord;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesPickingMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FbaCartonService {
    private final FbaCartonMapper cartons;
    private final SalesPickingMapper picks;
    private final SalesPickingService picking;
    private final SalesDispatchMapper packing;
    private final WmsOrderStore orders;
    private final InventoryStore inventory;
    private final BatchQualityGuard quality;

    public FbaCartonService(FbaCartonMapper cartons, SalesPickingMapper picks, SalesPickingService picking,
            SalesDispatchMapper packing, WmsOrderStore orders, InventoryStore inventory, BatchQualityGuard quality) {
        this.cartons = cartons; this.picks = picks; this.picking = picking; this.packing = packing;
        this.orders = orders; this.inventory = inventory; this.quality = quality;
    }

    @Transactional
    public Carton pack(String tenant, String outbound, String operator, Command command) {
        if (picks.lockWork(tenant, outbound) == null) throw new BizException("FBA_PICK_NOT_FOUND", "FBA 拣货作业不存在");
        var work = picking.get(tenant, outbound);
        if (!work.sourceType().equals("FBA_SHIPMENT")) throw new BizException("FBA_PICK_NOT_FOUND", "当前作业不是 FBA 备货");
        String requestedCode = command.cartonCode() == null || command.cartonCode().isBlank() ? null : command.cartonCode().strip();
        Map<String, PackLine> requested = lines(command.lines());
        var existing = cartons.findRequest(tenant, command.requestId());
        if (existing != null) {
            Map<String, PackLine> stored = packing.lines(tenant, existing.cartonId()).stream().map(l ->
                    new PackLine(l.pickId(), l.sellerSku(), l.batchNo(), l.quantity())).collect(Collectors.toMap(PackLine::pickId, Function.identity()));
            if (!existing.outboundId().equals(outbound) || !Objects.equals(existing.requestedCode(), requestedCode) || !stored.equals(requested)) throw conflict();
            return detail(tenant, existing);
        }
        if (!List.of("PICKING", "PICKED", "SHORT_PICK").contains(work.status())) {
            throw new BizException("FBA_CARTON_STATUS_INVALID", "当前作业须有有效实拣且未暂停，才能装箱");
        }
        for (String sku : command.lines().stream().map(PackLine::sellerSku).distinct().sorted().toList()) {
            inventory.lockBalance(tenant, work.warehouseId(), sku)
                    .orElseThrow(() -> new BizException("FBA_CARTON_SCAN_INVALID", "SKU 不属于当前实拣货物"));
        }
        for (PackLine line : command.lines()) {
            var pick = work.picks().stream().filter(p -> p.requestId().equals(line.pickId())).findFirst()
                    .orElseThrow(() -> new BizException("FBA_CARTON_PICK_INVALID", "实拣记录不属于当前备货作业"));
            if (!pick.sellerSku().equals(line.sellerSku()) || !pick.batchNo().equals(line.batchNo())) {
                throw new BizException("FBA_CARTON_SCAN_INVALID", "SKU 或批次与实拣标签不符");
            }
            quality.requireClear(tenant, pick.batchNo());
            if (line.quantity() > pick.quantity() - pick.returnedQuantity() - packing.packedQuantity(tenant, pick.requestId())) {
                throw new BizException("FBA_CARTON_EXCEEDS_PICKED", "装箱数量超过该实拣记录尚未装箱的数量");
            }
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String code = requestedCode == null ? "CTN-" + id : requestedCode;
        orders.saveOutboundPackage(new OutboundPackage(id, tenant, outbound, work.warehouseId(), null, null, null,
                OutboundPackageStatus.PACKED, "FBA 内部箱 " + code, now, null, now, now));
        var record = new CartonRecord(id, command.requestId(), code, requestedCode, outbound, work.sourceId(), "PACKED", operator, now);
        if (cartons.insert(tenant, record) != 1) throw new BizException("FBA_CARTON_CONFLICT", "内部箱号或操作编号已用于其他装箱，请查询原记录");
        if (cartons.registerInternalCode(tenant, id, code) != 1) throw new BizException("FBA_CARTON_CONFLICT", "该箱号已用于其他内部箱或 FBA 箱标");
        for (PackLine line : command.lines()) {
            var pick = work.picks().stream().filter(p -> p.requestId().equals(line.pickId())).findFirst().orElseThrow();
            var allocation = work.allocations().stream().filter(a -> a.allocationId().equals(pick.allocationId())).findFirst().orElseThrow();
            packing.insertPick(tenant, id, pick.requestId(), line.quantity());
            orders.saveOutboundPackageLine(new OutboundPackageLine(UUID.randomUUID().toString(), id,
                    allocation.lineId(), pick.sellerSku(), line.quantity(), pick.batchNo()));
        }
        return detail(tenant, record);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Carton> list(String tenant, FbaCartonMapper.Filter filter) {
        return cartons.list(tenant, filter).stream().map(c -> detail(tenant, c)).toList();
    }
    private Map<String, PackLine> lines(List<PackLine> lines) {
        if (lines.isEmpty() || lines.stream().anyMatch(l -> l.quantity() <= 0)
                || lines.stream().map(PackLine::pickId).distinct().count() != lines.size()) {
            throw new BizException("FBA_CARTON_LINES_INVALID", "箱内明细不能为空，实拣记录不能重复，数量须大于零");
        }
        return lines.stream().collect(Collectors.toMap(PackLine::pickId, Function.identity()));
    }
    private Carton detail(String tenant, CartonRecord c) {
        return new Carton(c.cartonId(), c.requestId(), c.cartonCode(), c.outboundId(), c.fbaShipmentId(), c.status(),
                c.operatorId(), c.createdAt(), packing.lines(tenant, c.cartonId()), c.fbaBoxNumber(), c.bindingActive(), c.boundBy(), c.boundAt());
    }
    private BizException conflict() { return new BizException("FBA_CARTON_REQUEST_CONFLICT", "该操作编号已用于不同的装箱内容"); }
    public record PackLine(String pickId, String sellerSku, String batchNo, int quantity) {}
    public record Command(String requestId, String cartonCode, List<PackLine> lines) {}
    public record Carton(String cartonId, String requestId, String cartonCode, String outboundId, String fbaShipmentId,
                         String status, String operatorId, Instant createdAt, List<WarehouseShipments.Line> lines,
                         String fbaBoxNumber, boolean bindingActive, String boundBy, Instant boundAt) {}
}
