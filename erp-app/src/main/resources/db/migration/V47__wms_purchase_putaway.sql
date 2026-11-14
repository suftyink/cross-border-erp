ALTER TABLE wms_warehouse_location ADD COLUMN IF NOT EXISTS purpose VARCHAR(16) NOT NULL DEFAULT 'GOOD';
ALTER TABLE wms_warehouse_location ADD COLUMN IF NOT EXISTS location_code VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_location_code
    ON wms_warehouse_location(tenant_id, warehouse_id, location_code);

ALTER TABLE wms_purchase_inspection ADD COLUMN IF NOT EXISTS putaway_quantity INT NOT NULL DEFAULT 0
    CHECK (putaway_quantity >= 0 AND putaway_quantity <= good_quantity);

CREATE TABLE IF NOT EXISTS wms_batch_location_balance (
    tenant_id    VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL,
    location_id  VARCHAR(64) NOT NULL,
    batch_no     VARCHAR(64) NOT NULL,
    seller_sku   VARCHAR(128) NOT NULL,
    quantity     INT NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (tenant_id, batch_no, location_id)
);

CREATE INDEX IF NOT EXISTS idx_wms_batch_location_sku
    ON wms_batch_location_balance(tenant_id, warehouse_id, seller_sku);

ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS source_location_id VARCHAR(64);
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS target_location_id VARCHAR(64);
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS before_location_quantity INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS after_location_quantity INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS before_pending_putaway INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS after_pending_putaway INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS available_delta INT;

CREATE INDEX IF NOT EXISTS idx_wms_inventory_txn_batch
    ON wms_inventory_transaction(tenant_id, batch_no, created_at);
CREATE INDEX IF NOT EXISTS idx_wms_inventory_txn_putaway
    ON wms_inventory_transaction(tenant_id, warehouse_id, seller_sku, created_at DESC)
    WHERE transaction_type = 'PURCHASE_PUTAWAY';
