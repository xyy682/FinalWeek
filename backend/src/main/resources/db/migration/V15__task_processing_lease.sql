ALTER TABLE background_task
    ADD COLUMN processing_owner VARCHAR(120) NULL AFTER execution_round,
    ADD COLUMN processing_lease_until TIMESTAMP(6) NULL AFTER processing_owner,
    ADD INDEX idx_background_task_processing_lease (status, processing_lease_until);

UPDATE background_task
SET processing_owner = 'flyway-recovery',
    processing_lease_until = CURRENT_TIMESTAMP(6)
WHERE status = 'PROCESSING';