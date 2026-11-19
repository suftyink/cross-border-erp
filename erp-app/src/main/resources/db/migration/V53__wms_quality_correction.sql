CREATE TABLE IF NOT EXISTS wms_quality_correction (
    tenant_id VARCHAR(64) NOT NULL,
    correction_id VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 0),
    excluded_missing_quantity INT NOT NULL DEFAULT 0 CHECK (excluded_missing_quantity >= 0),
    reason VARCHAR(500) NOT NULL,
    original_inspection_id VARCHAR(64) NOT NULL,
    original_inspection TEXT NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_by VARCHAR(64),
    released_at TIMESTAMP WITH TIME ZONE,
    reversed_quantity INT NOT NULL DEFAULT 0 CHECK (reversed_quantity >= 0),
    direct_pending_quantity INT NOT NULL DEFAULT 0 CHECK (direct_pending_quantity >= 0),
    withdrawn_by VARCHAR(64),
    withdrawn_at TIMESTAMP WITH TIME ZONE,
    good_quantity INT,
    defective_quantity INT,
    inspected_by VARCHAR(64),
    inspected_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, correction_id),
    CHECK (direct_pending_quantity + reversed_quantity <= quantity),
    CHECK (inspected_at IS NULL OR (withdrawn_at IS NOT NULL AND good_quantity >= 0 AND defective_quantity >= 0
        AND good_quantity + defective_quantity = quantity))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_wms_quality_correction_active
    ON wms_quality_correction(tenant_id, batch_no) WHERE inspected_at IS NULL;
CREATE TABLE IF NOT EXISTS wms_quality_correction_movement (
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    correction_id VARCHAR(64) NOT NULL,
    original_operation_id VARCHAR(64) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    location_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, correction_id) REFERENCES wms_quality_correction(tenant_id, correction_id)
);
ALTER TABLE wms_outbound_allocation ADD COLUMN IF NOT EXISTS cancelled_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE wms_outbound_allocation ADD COLUMN IF NOT EXISTS quality_replacement BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wms_outbound_allocation ADD CONSTRAINT ck_wms_allocation_cancelled
    CHECK (cancelled_quantity >= 0 AND cancelled_quantity + picked_quantity + short_quantity <= planned_quantity);
ALTER TABLE wms_actual_pick ADD COLUMN IF NOT EXISTS returned_quantity INT NOT NULL DEFAULT 0
    CHECK (returned_quantity >= 0 AND returned_quantity <= quantity);
