-- Finance step 2: vouchers entered by hand. A receipt or payment can name the flat it came from or went
-- to (e.g. hall rent from A-101); this is for finding it later, the books themselves don't depend on it.
ALTER TABLE finance_voucher
    ADD COLUMN flat_id BIGINT REFERENCES flat(id);

CREATE INDEX idx_finance_voucher_flat ON finance_voucher(flat_id) WHERE flat_id IS NOT NULL;
