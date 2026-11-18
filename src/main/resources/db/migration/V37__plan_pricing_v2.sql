-- Subscription pricing v2: a price per society per period covering a number of flats, plus a rate per
-- extra flat; one featured plan; a recommendation by flat count; a FREE plan.

ALTER TABLE plan RENAME COLUMN price_per_flat_monthly TO price_monthly;
ALTER TABLE plan RENAME COLUMN price_per_flat_yearly TO price_yearly;
ALTER TABLE plan DROP CONSTRAINT plan_prices_check;
ALTER TABLE plan DROP COLUMN minimum_monthly;
-- NULL: the price covers any number of flats.
ALTER TABLE plan ADD COLUMN included_flats INTEGER;
-- Per flat beyond the included ones, per period; NULL with included flats means none can be added past
-- them (set max_flats to say so).
ALTER TABLE plan ADD COLUMN extra_flat_monthly NUMERIC(10,2);
ALTER TABLE plan ADD COLUMN extra_flat_yearly NUMERIC(10,2);
ALTER TABLE plan ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false;
-- The plan is the recommended one from this many flats (the highest not above the count wins).
ALTER TABLE plan ADD COLUMN recommended_from_flats INTEGER NOT NULL DEFAULT 1;
ALTER TABLE plan ADD CONSTRAINT plan_prices_check CHECK (
    price_monthly >= 0 AND price_yearly >= 0
    AND (extra_flat_monthly IS NULL OR extra_flat_monthly >= 0)
    AND (extra_flat_yearly IS NULL OR extra_flat_yearly >= 0)
);
ALTER TABLE plan ADD CONSTRAINT plan_included_flats_check CHECK (included_flats IS NULL OR included_flats > 0);
ALTER TABLE plan ADD CONSTRAINT plan_recommended_check CHECK (recommended_from_flats > 0);
-- At most one featured plan.
CREATE UNIQUE INDEX plan_featured_idx ON plan (featured) WHERE featured;

UPDATE plan SET price_monthly = 499, price_yearly = 4990, included_flats = 30, extra_flat_monthly = 10, extra_flat_yearly = 100,
    max_flats = NULL, recommended_from_flats = 16, trial_days = 30, modules = 'MAINTENANCE', sort_order = 1,
    description = 'For small buildings: visitors, notices, complaints and maintenance billing with online payment.'
WHERE code = 'BASIC';
UPDATE plan SET price_monthly = 699, price_yearly = 6990, included_flats = 50, extra_flat_monthly = 12, extra_flat_yearly = 120,
    max_flats = NULL, recommended_from_flats = 31, trial_days = 30, modules = 'MAINTENANCE,FINANCE,POLICIES', sort_order = 2,
    featured = true,
    description = 'Everything in Basic, plus double-entry accounts, bank reconciliation and society rules and policies.'
WHERE code = 'STANDARD';
UPDATE plan SET price_monthly = 999, price_yearly = 9990, included_flats = 75, extra_flat_monthly = 14, extra_flat_yearly = 140,
    max_flats = NULL, recommended_from_flats = 151, trial_days = 30, modules = 'ALL', sort_order = 3,
    description = 'Everything in Standard, plus meetings with e-voting, every module we add later and priority support.'
WHERE code = 'PREMIUM';
INSERT INTO plan (code, name, description, price_monthly, price_yearly, included_flats, max_flats, recommended_from_flats,
                  trial_days, modules, sort_order, created_at)
SELECT 'FREE', 'Free', 'For very small buildings: residents, visitors, notices and complaints.', 0, 0, 15, 15, 1, 0, '', 0, now()
WHERE NOT EXISTS (SELECT 1 FROM plan WHERE code = 'FREE');

-- Invoices freeze the new price parts: the base price, the flats it covers, and the extra flats' rate.
ALTER TABLE platform_invoice RENAME COLUMN minimum_charge TO base_price;
ALTER TABLE platform_invoice RENAME COLUMN rate_per_flat TO extra_flat_rate;
ALTER TABLE platform_invoice ALTER COLUMN extra_flat_rate DROP NOT NULL;
ALTER TABLE platform_invoice ADD COLUMN included_flats INTEGER;
ALTER TABLE platform_invoice ADD COLUMN extra_flats INTEGER NOT NULL DEFAULT 0;
