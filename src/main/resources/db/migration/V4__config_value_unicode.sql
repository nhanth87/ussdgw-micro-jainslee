-- Widen ussd_config.config_value for Unicode bridge messages (VI / Amharic / Persian).
-- H2 MODE=PostgreSQL + PostgreSQL both accept SET DATA TYPE.
ALTER TABLE ussd_config ALTER COLUMN config_value SET DATA TYPE VARCHAR(4096);
