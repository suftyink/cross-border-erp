package com.aidotnet.erp.wms.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class WmsSkuUsageProbe extends SqlSkuUsageProbe {
    public WmsSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "wms",
                new Source("库存余额", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.warehouse_id AS VARCHAR) AS reference_id FROM wms_inventory_balance t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.check_id AS VARCHAR) AS reference_id FROM wms_stock_check_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.prediction_id AS VARCHAR) AS reference_id FROM wms_inventory_prediction t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("库存流水", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.transaction_id AS VARCHAR) AS reference_id FROM wms_inventory_transaction t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM wms_inbound_order_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM wms_outbound_order_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.package_line_id AS VARCHAR) AS reference_id FROM wms_outbound_package_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.check_id AS VARCHAR) AS reference_id FROM wms_quality_check t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.return_id AS VARCHAR) AS reference_id FROM wms_defective_return t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.repair_id AS VARCHAR) AS reference_id FROM wms_product_repair t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.movement_id AS VARCHAR) AS reference_id FROM wms_inventory_movement t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM wms_transfer_order_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM wms_stock_check_order_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.warehouse_id AS VARCHAR) AS reference_id FROM wms_batch_location_balance t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.return_id AS VARCHAR) AS reference_id FROM wms_purchase_supplier_return t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.allocation_id AS VARCHAR) AS reference_id FROM wms_outbound_allocation t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("仓储记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.receipt_id AS VARCHAR) AS reference_id FROM wms_sales_return_receipt t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
