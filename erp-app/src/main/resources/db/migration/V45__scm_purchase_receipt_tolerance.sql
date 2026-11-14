ALTER TABLE scm_supplier ADD COLUMN IF NOT EXISTS receipt_tolerance_percent DECIMAL(5, 2) NOT NULL DEFAULT 0
    CHECK (receipt_tolerance_percent BETWEEN 0 AND 100);
ALTER TABLE scm_supplier ADD COLUMN IF NOT EXISTS receipt_tolerance_updated_by VARCHAR(64);
ALTER TABLE scm_supplier ADD COLUMN IF NOT EXISTS receipt_tolerance_updated_at TIMESTAMP;
ALTER TABLE scm_purchase_order ADD COLUMN IF NOT EXISTS receipt_tolerance_percent DECIMAL(5, 2) NOT NULL DEFAULT 0
    CHECK (receipt_tolerance_percent BETWEEN 0 AND 100);
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS original_quantity INT;
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS over_received_before_amendment INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS scm_purchase_amendment_line (
    amendment_id      VARCHAR(64)   NOT NULL,
    tenant_id         VARCHAR(64)   NOT NULL,
    po_id             VARCHAR(64)   NOT NULL,
    line_id           VARCHAR(64)   NOT NULL,
    previous_quantity INT           NOT NULL,
    revised_quantity  INT           NOT NULL,
    reason            VARCHAR(500)  NOT NULL,
    operator_id       VARCHAR(64)   NOT NULL,
    created_at        TIMESTAMP     NOT NULL,
    PRIMARY KEY (amendment_id, line_id)
);

CREATE INDEX IF NOT EXISTS idx_scm_purchase_amendment_order
    ON scm_purchase_amendment_line(tenant_id, po_id, created_at);
