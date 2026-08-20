ALTER TABLE mock_exam
    ADD COLUMN quality_policy_version VARCHAR(30) NOT NULL DEFAULT 'v1' AFTER request_hash,
    ADD COLUMN history_similarity_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.8200 AFTER quality_policy_version,
    ADD COLUMN pdf_template_version VARCHAR(30) NOT NULL DEFAULT 'v1' AFTER history_similarity_threshold,
    ADD COLUMN formula_policy_version VARCHAR(30) NOT NULL DEFAULT 'v1' AFTER pdf_template_version,
    ADD COLUMN error_message VARCHAR(1000) NULL AFTER error_code;
