package com.aidotnet.erp.oms.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class OmsSkuUsageProbe extends SqlSkuUsageProbe {
    public OmsSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "oms",
                new Source("销售订单", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM oms_order_line t JOIN oms_sales_order p ON p.order_id=t.order_id WHERE p.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("旧履约包裹", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.package_line_id AS VARCHAR) AS reference_id FROM oms_fulfillment_package_line t JOIN oms_fulfillment_package p ON p.package_id=t.package_id JOIN oms_fulfillment_plan f ON f.plan_id=p.plan_id WHERE f.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("销售订单记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.package_id AS VARCHAR) AS reference_id FROM oms_warehouse_shipment_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
