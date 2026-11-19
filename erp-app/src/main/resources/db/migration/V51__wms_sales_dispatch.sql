CREATE TABLE IF NOT EXISTS wms_sales_package_pick (
    tenant_id VARCHAR(64) NOT NULL,
    package_id VARCHAR(64) NOT NULL,
    pick_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (tenant_id, package_id, pick_id),
    FOREIGN KEY (tenant_id, pick_id) REFERENCES wms_actual_pick(tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_package_pick_source ON wms_sales_package_pick(tenant_id, pick_id);

-- 发货结果同时充当持久化待回写凭证，与库存扣减同事务提交。
CREATE TABLE IF NOT EXISTS wms_sales_shipment (
    tenant_id VARCHAR(64) NOT NULL,
    package_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    shipped_at TIMESTAMP WITH TIME ZONE NOT NULL,
    oms_sync_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (oms_sync_status IN ('PENDING', 'SYNCED')),
    sync_error VARCHAR(256),
    sync_attempts INT NOT NULL DEFAULT 0,
    synced_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, package_id),
    FOREIGN KEY (tenant_id, outbound_id) REFERENCES wms_sales_pick_work(tenant_id, outbound_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_shipment_pending ON wms_sales_shipment(shipped_at) WHERE oms_sync_status = 'PENDING';

CREATE TABLE IF NOT EXISTS oms_warehouse_shipment (
    tenant_id VARCHAR(64) NOT NULL,
    package_id VARCHAR(64) NOT NULL,
    sales_order_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    shipped_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, package_id)
);
CREATE INDEX IF NOT EXISTS idx_oms_warehouse_shipment_order ON oms_warehouse_shipment(tenant_id, sales_order_id);
CREATE TABLE IF NOT EXISTS oms_warehouse_shipment_line (
    tenant_id VARCHAR(64) NOT NULL,
    package_id VARCHAR(64) NOT NULL,
    pick_id VARCHAR(64) NOT NULL,
    sales_line_id VARCHAR(64) NOT NULL,
    seller_sku VARCHAR(128) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (tenant_id, package_id, pick_id),
    FOREIGN KEY (tenant_id, package_id) REFERENCES oms_warehouse_shipment(tenant_id, package_id)
);
