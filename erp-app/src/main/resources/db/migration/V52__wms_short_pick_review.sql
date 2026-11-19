ALTER TABLE wms_sales_pick_work ADD COLUMN IF NOT EXISTS allow_batch_substitution BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wms_outbound_allocation ADD COLUMN IF NOT EXISTS short_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE wms_outbound_allocation ADD CONSTRAINT ck_wms_allocation_short
    CHECK (short_quantity >= 0 AND short_quantity <= planned_quantity - picked_quantity);
ALTER TABLE wms_batch_location_balance ADD COLUMN IF NOT EXISTS review_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE wms_batch_location_balance ADD CONSTRAINT ck_wms_batch_review
    CHECK (review_quantity >= 0 AND review_quantity <= quantity - reserved_quantity);
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS pending_review_delta INT;

CREATE TABLE IF NOT EXISTS wms_short_pick (
    tenant_id VARCHAR(64) NOT NULL,
    short_pick_id VARCHAR(64) NOT NULL,
    outbound_id VARCHAR(64) NOT NULL,
    allocation_id VARCHAR(64) NOT NULL,
    short_quantity INT NOT NULL CHECK (short_quantity > 0),
    reason VARCHAR(500) NOT NULL,
    reported_by VARCHAR(64) NOT NULL,
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    replacement_request_id VARCHAR(64),
    replacement_allocation_id VARCHAR(64),
    replaced_by VARCHAR(64),
    replaced_at TIMESTAMP WITH TIME ZONE,
    found_quantity INT,
    missing_quantity INT,
    counted_by VARCHAR(64),
    counted_at TIMESTAMP WITH TIME ZONE,
    count_remark VARCHAR(500),
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, short_pick_id),
    UNIQUE (tenant_id, replacement_request_id),
    FOREIGN KEY (tenant_id, outbound_id) REFERENCES wms_sales_pick_work(tenant_id, outbound_id),
    FOREIGN KEY (tenant_id, allocation_id) REFERENCES wms_outbound_allocation(tenant_id, allocation_id),
    FOREIGN KEY (tenant_id, replacement_allocation_id) REFERENCES wms_outbound_allocation(tenant_id, allocation_id),
    CHECK ((found_quantity IS NULL AND missing_quantity IS NULL) OR
           (found_quantity >= 0 AND missing_quantity >= 0 AND found_quantity + missing_quantity = short_quantity)),
    CHECK (confirmed_at IS NULL OR counted_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_wms_short_pick_work ON wms_short_pick(tenant_id, outbound_id, reported_at);
