CREATE TABLE IF NOT EXISTS wms_purchase_inspection (
    inspection_id       VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    batch_no            VARCHAR(64) NOT NULL,
    sample_checked      INT NOT NULL CHECK (sample_checked > 0),
    sample_passed       INT NOT NULL CHECK (sample_passed >= 0),
    sample_failed       INT NOT NULL CHECK (sample_failed >= 0),
    sample_operator_id  VARCHAR(64) NOT NULL,
    sampled_at          TIMESTAMP NOT NULL,
    recheck_method      VARCHAR(16),
    recheck_checked     INT,
    recheck_passed      INT,
    recheck_failed      INT,
    recheck_operator_id VARCHAR(64),
    rechecked_at        TIMESTAMP,
    good_quantity       INT,
    defective_quantity  INT,
    disposition_operator_id VARCHAR(64),
    disposed_at         TIMESTAMP,
    UNIQUE (tenant_id, batch_no)
);

ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS batch_no VARCHAR(64);
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS operator_id VARCHAR(64);
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS pending_inspection_delta INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS pending_putaway_delta INT;
ALTER TABLE wms_inventory_transaction ADD COLUMN IF NOT EXISTS quarantined_delta INT;
