ALTER TABLE wms_batch_location_balance ADD COLUMN IF NOT EXISTS reserved_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE wms_batch_location_balance ADD COLUMN IF NOT EXISTS picked_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE wms_batch_location_balance ADD CONSTRAINT ck_wms_batch_reservation
    CHECK (picked_quantity >= 0 AND reserved_quantity >= picked_quantity AND quantity >= reserved_quantity);

CREATE TABLE IF NOT EXISTS wms_sales_pick_work (
    tenant_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    sales_order_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, outbound_id),
    UNIQUE (tenant_id, sales_order_id)
);
CREATE TABLE IF NOT EXISTS wms_outbound_allocation (
    tenant_id VARCHAR(64) NOT NULL,
    allocation_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    sales_line_id VARCHAR(64) NOT NULL,
    seller_sku VARCHAR(128) NOT NULL,
    location_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    planned_quantity INT NOT NULL CHECK (planned_quantity > 0),
    picked_quantity INT NOT NULL DEFAULT 0 CHECK (picked_quantity >= 0 AND picked_quantity <= planned_quantity),
    PRIMARY KEY (tenant_id, allocation_id),
    FOREIGN KEY (tenant_id, outbound_id) REFERENCES wms_sales_pick_work(tenant_id, outbound_id),
    FOREIGN KEY (tenant_id, batch_no, location_id) REFERENCES wms_batch_location_balance(tenant_id, batch_no, location_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_allocation_work ON wms_outbound_allocation(tenant_id, outbound_id);
CREATE TABLE IF NOT EXISTS wms_actual_pick (
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    allocation_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, allocation_id) REFERENCES wms_outbound_allocation(tenant_id, allocation_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_actual_pick_work ON wms_actual_pick(tenant_id, outbound_id);
