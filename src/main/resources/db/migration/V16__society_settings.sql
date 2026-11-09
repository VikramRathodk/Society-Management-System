-- Per-society settings. What a setting is (type, default, limits, label) lives in code
-- (SocietySettingCatalog); this table only holds the values a society has set. A setting without a
-- row uses its default, so adding a new setting needs no migration.
CREATE TABLE society_setting (
    society_id BIGINT NOT NULL REFERENCES society(id) ON DELETE CASCADE,
    setting_key VARCHAR(100) NOT NULL,
    setting_value VARCHAR(1000) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by BIGINT REFERENCES users(id),
    PRIMARY KEY (society_id, setting_key)
);
