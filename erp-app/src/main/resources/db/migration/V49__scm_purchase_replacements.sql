ALTER TABLE wms_purchase_supplier_return ADD COLUMN IF NOT EXISTS replace BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wms_inbound_order ADD COLUMN IF NOT EXISTS replacement BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS replacement_pending_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS replacement_received_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS replacement_tolerance_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE scm_purchase_order_line ADD COLUMN IF NOT EXISTS replacement_amendment_quantity INT NOT NULL DEFAULT 0;
