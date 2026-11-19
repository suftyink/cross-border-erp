CREATE TABLE IF NOT EXISTS crm_recall_batch_scope (
    tenant_id VARCHAR(64) NOT NULL, batch_no VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, batch_no)
);
CREATE TABLE IF NOT EXISTS crm_batch_recall (
    tenant_id VARCHAR(64) NOT NULL, recall_id VARCHAR(64) NOT NULL, batch_no VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL, created_by VARCHAR(64) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, recall_id)
);
CREATE TABLE IF NOT EXISTS crm_batch_recall_line (
    tenant_id VARCHAR(64) NOT NULL, line_id VARCHAR(64) NOT NULL, recall_id VARCHAR(64) NOT NULL,
    sales_order_id VARCHAR(64) NOT NULL, outbound_id VARCHAR(64) NOT NULL, warehouse_id VARCHAR(64) NOT NULL,
    seller_sku VARCHAR(128) NOT NULL, batch_no VARCHAR(64) NOT NULL, source_receipt_id VARCHAR(64) NOT NULL,
    purchase_order_id VARCHAR(64) NOT NULL, expected_quantity INT NOT NULL CHECK (expected_quantity > 0),
    PRIMARY KEY (tenant_id, line_id), FOREIGN KEY (tenant_id, recall_id) REFERENCES crm_batch_recall(tenant_id, recall_id)
);
CREATE TABLE IF NOT EXISTS crm_batch_recall_source (
    tenant_id VARCHAR(64) NOT NULL, recall_id VARCHAR(64) NOT NULL, line_id VARCHAR(64) NOT NULL,
    package_id VARCHAR(64) NOT NULL, outbound_id VARCHAR(64) NOT NULL, sales_order_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL, pick_id VARCHAR(64) NOT NULL, sales_line_id VARCHAR(64) NOT NULL,
    seller_sku VARCHAR(128) NOT NULL, location_id VARCHAR(64) NOT NULL, location_code VARCHAR(128) NOT NULL,
    batch_no VARCHAR(64) NOT NULL, receipt_id VARCHAR(64) NOT NULL, purchase_order_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0), shipped_at TIMESTAMP WITH TIME ZONE NOT NULL, operator_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, package_id, pick_id), FOREIGN KEY (tenant_id, line_id) REFERENCES crm_batch_recall_line(tenant_id, line_id)
);
CREATE TABLE IF NOT EXISTS crm_recall_notification (
    tenant_id VARCHAR(64) NOT NULL, notification_id VARCHAR(64) NOT NULL, line_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('PLATFORM','EMAIL','PHONE','OTHER')), content VARCHAR(1000) NOT NULL,
    operator_id VARCHAR(64) NOT NULL, notified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, notification_id), FOREIGN KEY (tenant_id, line_id) REFERENCES crm_batch_recall_line(tenant_id, line_id)
);
CREATE TABLE IF NOT EXISTS crm_sales_return (
    tenant_id VARCHAR(64) NOT NULL, return_id VARCHAR(64) NOT NULL, recall_id VARCHAR(64) NOT NULL,
    recall_line_id VARCHAR(64) NOT NULL, sales_order_id VARCHAR(64) NOT NULL, outbound_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL, seller_sku VARCHAR(128) NOT NULL, expected_batch_no VARCHAR(64) NOT NULL,
    source_receipt_id VARCHAR(64) NOT NULL, purchase_order_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0), received_quantity INT NOT NULL DEFAULT 0,
    confirmed_quantity INT NOT NULL DEFAULT 0, created_by VARCHAR(64) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, return_id), FOREIGN KEY (tenant_id, recall_line_id) REFERENCES crm_batch_recall_line(tenant_id, line_id),
    CHECK (confirmed_quantity >= 0 AND received_quantity >= confirmed_quantity AND quantity >= received_quantity)
);
CREATE TABLE IF NOT EXISTS crm_sales_return_receipt (
    tenant_id VARCHAR(64) NOT NULL, receipt_id VARCHAR(64) NOT NULL, return_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL, quantity INT NOT NULL CHECK (quantity > 0), operator_id VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL, verified_by VARCHAR(64), verified_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, receipt_id), FOREIGN KEY (tenant_id, return_id) REFERENCES crm_sales_return(tenant_id, return_id)
);
CREATE TABLE IF NOT EXISTS wms_sales_return_receipt (
    tenant_id VARCHAR(64) NOT NULL, receipt_id VARCHAR(64) NOT NULL, return_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL, quantity INT NOT NULL CHECK (quantity > 0), operator_id VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL, warehouse_id VARCHAR(64) NOT NULL, seller_sku VARCHAR(128) NOT NULL,
    source_receipt_id VARCHAR(64) NOT NULL, purchase_order_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, receipt_id)
);
CREATE INDEX IF NOT EXISTS idx_crm_recall_lines ON crm_batch_recall_line(tenant_id, recall_id);
CREATE INDEX IF NOT EXISTS idx_crm_sales_return_work ON crm_sales_return(tenant_id, recall_line_id);
CREATE INDEX IF NOT EXISTS idx_wms_sales_return_stock ON wms_sales_return_receipt(tenant_id, warehouse_id, seller_sku, batch_no);
