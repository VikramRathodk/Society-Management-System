-- The society's UPI ID (VPA) and payee name, shown as a pay-by-UPI QR code on its maintenance bills.
-- Residents still pay outside the app; the committee records the payment as before.
ALTER TABLE maintenance_settings
    ADD COLUMN upi_id VARCHAR(255),
    ADD COLUMN upi_payee_name VARCHAR(100);
