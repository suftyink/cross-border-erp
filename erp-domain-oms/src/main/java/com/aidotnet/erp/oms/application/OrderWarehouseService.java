package com.aidotnet.erp.oms.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.SalesOrderPicking;
import com.aidotnet.erp.oms.domain.OrderStatus;
import com.aidotnet.erp.oms.domain.SalesOrder;
import com.aidotnet.erp.oms.infrastructure.OrderStore;
import com.aidotnet.erp.oms.infrastructure.FulfillmentPlanStore;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderWarehouseService {
    private final OrderStore orders;
    private final FulfillmentPlanStore plans;
    private final SalesOrderPicking picking;
    private final WarehouseShipmentService shipments;
    public OrderWarehouseService(OrderStore orders, FulfillmentPlanStore plans, SalesOrderPicking picking,
                                  WarehouseShipmentService shipments) {
        this.orders = orders;
        this.plans = plans;
        this.picking = picking;
        this.shipments = shipments;
    }

    @Transactional
    public SalesOrderPicking.Work dispatch(String tenantId, String orderId, String warehouseId, String operatorId,
                                            boolean allowBatchSubstitution) {
        orders.lock(tenantId, orderId);
        SalesOrder order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new BizException("ORDER_NOT_FOUND", "销售订单不存在"));
        if (order.status() != OrderStatus.PAID) {
            throw new BizException("ORDER_STATUS_INVALID", "订单完成审核和付款后才可下发仓储作业");
        }
        if (plans.findByOrderId(tenantId, orderId).isPresent()) {
            throw new BizException("ORDER_LEGACY_PLAN_EXISTS", "订单已有履约计划，请先完成原履约流程");
        }
        if (order.lines().isEmpty() || order.lines().stream().anyMatch(l -> l.quantity() <= 0 || l.sellerSku() == null || l.sellerSku().isBlank())
                || order.lines().stream().map(l -> l.lineId()).distinct().count() != order.lines().size()) {
            throw new BizException("ORDER_LINES_INVALID", "订单明细需要唯一行号、SKU 和正数数量");
        }
        return picking.dispatch(tenantId, orderId, warehouseId, operatorId, order.lines().stream()
                .map(l -> new SalesOrderPicking.Line(l.lineId(), l.sellerSku(), l.quantity())).toList(), allowBatchSubstitution);
    }

    @Transactional
    public Optional<SalesOrder> readShipmentResult(String tenantId, String orderId) {
        orders.lock(tenantId, orderId);
        if (!picking.exists(tenantId, orderId)) return Optional.empty();
        shipments.refresh(tenantId, orderId);
        return orders.find(tenantId, orderId);
    }

    public boolean hasWarehouseWork(String tenantId, String orderId) {
        return picking.exists(tenantId, orderId);
    }
}
