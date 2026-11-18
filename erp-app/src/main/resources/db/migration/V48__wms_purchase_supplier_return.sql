ALTER TABLE wms_purchase_inspection ADD COLUMN IF NOT EXISTS isolated_quantity INT NOT NULL DEFAULT 0
    CHECK (isolated_quantity >= 0 AND isolated_quantity <= defective_quantity);
ALTER TABLE wms_purchase_inspection ADD COLUMN IF NOT EXISTS returned_quantity INT NOT NULL DEFAULT 0
    CHECK (returned_quantity >= 0 AND returned_quantity <= isolated_quantity);

CREATE TABLE IF NOT EXISTS wms_purchase_supplier_return (
    tenant_id VARCHAR(64) NOT NULL,
    return_id VARCHAR(64) NOT NULL,
    receipt_id VARCHAR(64) NOT NULL,
    purchase_order_id VARCHAR(64) NOT NULL,
    purchase_order_line_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL,
    location_id VARCHAR(64) NOT NULL,
    location_code VARCHAR(128) NOT NULL,
    seller_sku VARCHAR(128) NOT NULL,
    requested_quantity INT NOT NULL CHECK (requested_quantity > 0),
    reason VARCHAR(500) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    shipped_quantity INT CHECK (shipped_quantity > 0 AND shipped_quantity <= requested_quantity),
    shipped_by VARCHAR(64),
    shipped_at TIMESTAMP,
    PRIMARY KEY (tenant_id, return_id)
);

CREATE INDEX IF NOT EXISTS idx_wms_supplier_return_batch_location
    ON wms_purchase_supplier_return(tenant_id, batch_no, location_id) WHERE shipped_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_wms_supplier_return_po
    ON wms_purchase_supplier_return(tenant_id, purchase_order_id, requested_at);
