ALTER TABLE crm_sales_return_receipt ALTER COLUMN batch_no DROP NOT NULL;
ALTER TABLE crm_sales_return_receipt ADD COLUMN IF NOT EXISTS identified_batch_no VARCHAR(64);
ALTER TABLE crm_sales_return_receipt ADD COLUMN IF NOT EXISTS quarantine_location_id VARCHAR(64);
ALTER TABLE crm_sales_return_receipt ADD COLUMN IF NOT EXISTS quarantine_location_code VARCHAR(128);
ALTER TABLE crm_sales_return_receipt ADD COLUMN IF NOT EXISTS unidentified_reason VARCHAR(500);
UPDATE crm_sales_return_receipt SET identified_batch_no = batch_no WHERE verified_at IS NOT NULL AND batch_no IS NOT NULL AND identified_batch_no IS NULL;
ALTER TABLE crm_sales_return_receipt ADD CONSTRAINT ck_crm_return_unidentified
    CHECK (batch_no IS NOT NULL OR (quarantine_location_id IS NOT NULL AND quarantine_location_code IS NOT NULL AND unidentified_reason IS NOT NULL AND LENGTH(TRIM(unidentified_reason)) > 0));

ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS expected_batch_no VARCHAR(64);
UPDATE wms_sales_return_receipt SET expected_batch_no = batch_no WHERE expected_batch_no IS NULL;
ALTER TABLE wms_sales_return_receipt ALTER COLUMN expected_batch_no SET NOT NULL;
ALTER TABLE wms_sales_return_receipt ALTER COLUMN batch_no DROP NOT NULL;
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS identified_batch_no VARCHAR(64);
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS quarantine_location_id VARCHAR(64);
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS quarantine_location_code VARCHAR(128);
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS unidentified_reason VARCHAR(500);
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS verified_by VARCHAR(64);
ALTER TABLE wms_sales_return_receipt ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_sales_return_receipt ADD CONSTRAINT ck_wms_return_unidentified
    CHECK (batch_no IS NOT NULL OR (quarantine_location_id IS NOT NULL AND quarantine_location_code IS NOT NULL AND unidentified_reason IS NOT NULL AND LENGTH(TRIM(unidentified_reason)) > 0));

ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS close_reason VARCHAR(500);
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_by VARCHAR(64);
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_expected_quantity INT;
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_received_quantity INT;
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_confirmed_quantity INT;
ALTER TABLE crm_batch_recall_line ADD COLUMN IF NOT EXISTS closed_unidentified_quantity INT;
ALTER TABLE crm_batch_recall_line ADD CONSTRAINT ck_crm_recall_closure
    CHECK (closed_at IS NULL OR (close_reason IS NOT NULL AND LENGTH(TRIM(close_reason)) > 0 AND closed_by IS NOT NULL
        AND closed_expected_quantity IS NOT NULL AND closed_received_quantity IS NOT NULL AND closed_confirmed_quantity IS NOT NULL
        AND closed_unidentified_quantity IS NOT NULL AND closed_expected_quantity > closed_confirmed_quantity
        AND closed_received_quantity >= closed_confirmed_quantity + closed_unidentified_quantity AND closed_unidentified_quantity > 0));
