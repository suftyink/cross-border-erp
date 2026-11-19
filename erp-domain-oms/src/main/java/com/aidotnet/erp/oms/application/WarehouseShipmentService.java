package com.aidotnet.erp.oms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.oms.domain.OrderStatus;
import com.aidotnet.erp.oms.infrastructure.OrderStore;
import com.aidotnet.erp.oms.infrastructure.mapper.WarehouseShipmentMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseShipmentService implements WarehouseShipments.Receiver {
    private final WarehouseShipments source;
    private final OrderStore orders;
    private final WarehouseShipmentMapper receipts;
    public WarehouseShipmentService(WarehouseShipments source, OrderStore orders, WarehouseShipmentMapper receipts) {
        this.source = source;
        this.orders = orders;
        this.receipts = receipts;
    }

    @Override
    @Transactional
    public void accept(String tenant, String packageId) {
        var shipment = source.get(tenant, packageId);
        if (!"SHIPPED".equals(shipment.status()) || shipment.shippedAt() == null || shipment.lines().isEmpty()) {
            throw new BizException("WAREHOUSE_SHIPMENT_NOT_READY", "包裹尚无实际发货结果");
        }
        orders.lock(tenant, shipment.salesOrderId());
        var order = orders.find(tenant, shipment.salesOrderId())
                .orElseThrow(() -> new BizException("ORDER_NOT_FOUND", "销售订单不存在"));
        if (receipts.exists(tenant, packageId)) return;
        if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.SHIPPED) {
            throw new BizException("WAREHOUSE_SHIPMENT_ORDER_STATUS", "订单状态异常，待核实后重试发货回写");
        }
        Map<String, Integer> shipped = new HashMap<>();
        receipts.totals(tenant, order.orderId()).forEach(l -> shipped.put(l.salesLineId(), l.quantity()));
        for (var line : shipment.lines()) {
            var salesLine = order.lines().stream().filter(l -> l.lineId().equals(line.salesLineId()) && l.sellerSku().equals(line.sellerSku()))
                    .findFirst().orElseThrow(() -> new BizException("WAREHOUSE_SHIPMENT_LINE_INVALID", "发货明细与销售订单不符"));
            int total = Math.addExact(shipped.getOrDefault(line.salesLineId(), 0), line.quantity());
            if (line.quantity() <= 0 || total > salesLine.quantity()) {
                throw new BizException("WAREHOUSE_SHIPMENT_EXCEEDS_ORDER", "发货结果超过订单应发数量");
            }
            shipped.put(line.salesLineId(), total);
        }
        receipts.insert(tenant, shipment);
        shipment.lines().forEach(line -> receipts.insertLine(tenant, packageId, line));
        boolean complete = order.lines().stream().allMatch(l -> shipped.getOrDefault(l.lineId(), 0) == l.quantity());
        receipts.updateOrder(tenant, order.orderId(), complete ? "SHIPPED" : "PAID", complete ? "SHIPPED" : "PARTIALLY_SHIPPED");
    }

    @Transactional
    public Fulfillment refresh(String tenant, String orderId) {
        orders.lock(tenant, orderId);
        requireOrder(tenant, orderId);
        for (var shipment : source.forOrder(tenant, orderId)) {
            if ("SHIPPED".equals(shipment.status())) accept(tenant, shipment.packageId());
        }
        return get(tenant, orderId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Fulfillment get(String tenant, String orderId) {
        var order = requireOrder(tenant, orderId);
        int quantity = receipts.totals(tenant, orderId).stream().mapToInt(l -> l.quantity()).sum();
        return new Fulfillment(orderId, order.status().name(), order.fulfillmentStatus(), quantity, source.forOrder(tenant, orderId));
    }
    private com.aidotnet.erp.oms.domain.SalesOrder requireOrder(String tenant, String orderId) {
        return orders.find(tenant, orderId).orElseThrow(() -> new BizException("ORDER_NOT_FOUND", "销售订单不存在"));
    }
    public record Fulfillment(String salesOrderId, String orderStatus, String fulfillmentStatus, int shippedQuantity,
                              List<WarehouseShipments.Shipment> packages) {}
}
