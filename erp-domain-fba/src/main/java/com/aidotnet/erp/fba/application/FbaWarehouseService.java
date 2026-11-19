package com.aidotnet.erp.fba.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.FbaWarehousePicking;
import com.aidotnet.erp.common.fba.FbaBoxLabels;
import com.aidotnet.erp.common.fba.FbaWarehouseShipments;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaShipmentResultMapper;
import com.aidotnet.erp.common.wms.SalesOrderPicking;
import com.aidotnet.erp.fba.domain.FbaShipmentStatus;
import com.aidotnet.erp.fba.domain.FbaShipment;
import com.aidotnet.erp.fba.infrastructure.FbaShipmentRepository;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaWarehouseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class FbaWarehouseService {
    private final FbaWarehouseMapper mapper;
    private final FbaShipmentRepository shipments;
    private final FbaWarehousePicking picking;
    private final FbaBoxLabels labels;
    private final FbaShipmentResultMapper results;

    public FbaWarehouseService(FbaWarehouseMapper mapper, FbaShipmentRepository shipments, FbaWarehousePicking picking, FbaBoxLabels labels, FbaShipmentResultMapper results) {
        this.mapper = mapper;
        this.shipments = shipments;
        this.picking = picking;
        this.labels = labels;
        this.results = results;
    }

    @Transactional
    public SalesOrderPicking.Work dispatch(String tenant, String id, String warehouse, String operator) {
        if (mapper.lockShipment(tenant, id) == null) throw notFound();
        var existing = picking.findFbaWork(tenant, id);
        if (existing != null) {
            if (!existing.warehouseId().equals(warehouse)) throw new BizException("FBA_WAREHOUSE_CONFLICT", "备货已下发，不能更换仓库");
            return existing;
        }
        var shipment = shipments.find(tenant, id).orElseThrow(this::notFound);
        if (shipment.status() != FbaShipmentStatus.SUBMITTED) {
            throw new BizException("FBA_WAREHOUSE_STATUS_INVALID", "仅已提交的 FBA 备货单可以下发仓储");
        }
        var items = shipments.listShipmentItems(tenant, id);
        if (items.isEmpty() || items.stream().anyMatch(i -> i.sellerSku() == null || i.sellerSku().isBlank() || i.quantity() <= 0)
                || items.stream().mapToLong(i -> i.quantity()).sum() != shipment.plannedQuantity()) {
            throw new BizException("FBA_WAREHOUSE_LINES_INVALID", "备货明细不能为空，SKU 和数量须有效，明细总数须等于计划数量");
        }
        return picking.dispatchFba(tenant, id, warehouse, operator,
                items.stream().map(i -> new SalesOrderPicking.Line(i.itemId(), i.sellerSku(), i.quantity())).toList());
    }

    @Transactional(readOnly = true)
    public SalesOrderPicking.Work get(String tenant, String id) {
        shipments.find(tenant, id).orElseThrow(this::notFound);
        return picking.findFbaWork(tenant, id);
    }
    @Transactional(readOnly = true)
    public List<Row> list(String tenant) {
        return shipments.list(tenant, null).stream().map(s -> new Row(s, picking.findFbaWork(tenant, s.fbaShipmentId()), labels.find(tenant, s.fbaShipmentId()), results.forShipment(tenant, s.fbaShipmentId()))).toList();
    }
    public record Row(FbaShipment shipment, SalesOrderPicking.Work warehouseWork, FbaBoxLabels.Plan boxLabelPlan, FbaWarehouseShipments.Shipment warehouseShipment) {}
    private BizException notFound() { return new BizException("FBA_SHIPMENT_NOT_FOUND", "FBA 备货单不存在"); }
}
