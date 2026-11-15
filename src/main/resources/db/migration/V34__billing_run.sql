-- Subscription & billing, step S5: the daily billing run. It remembers the last reminder per invoice, so a
-- restart or a second run on the same day never reminds twice.
ALTER TABLE platform_invoice ADD COLUMN last_reminded_on DATE;
