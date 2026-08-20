ALTER TABLE plan_generation_request
    ADD COLUMN knowledge_version_id BINARY(16) NULL AFTER course_id,
    ADD COLUMN background_task_id BINARY(16) NULL AFTER knowledge_version_id,
    ADD COLUMN request_json JSON NULL AFTER request_hash,
    ADD CONSTRAINT uk_plan_request_task UNIQUE (background_task_id),
    ADD CONSTRAINT fk_plan_request_knowledge_version FOREIGN KEY (knowledge_version_id)
        REFERENCES course_knowledge_version (id),
    ADD CONSTRAINT fk_plan_request_task FOREIGN KEY (background_task_id)
        REFERENCES background_task (id);

ALTER TABLE study_plan
    ADD COLUMN knowledge_version_id BINARY(16) NULL AFTER course_id;

UPDATE study_plan plan
JOIN course c ON c.id = plan.course_id
SET plan.knowledge_version_id = c.current_knowledge_version_id
WHERE plan.knowledge_version_id IS NULL;

ALTER TABLE study_plan
    MODIFY knowledge_version_id BINARY(16) NOT NULL,
    ADD CONSTRAINT fk_study_plan_knowledge_version FOREIGN KEY (knowledge_version_id)
        REFERENCES course_knowledge_version (id),
    ADD INDEX idx_study_plan_knowledge_version (knowledge_version_id);

ALTER TABLE chat_message
    ADD COLUMN background_task_id BINARY(16) NULL AFTER course_id,
    ADD CONSTRAINT uk_chat_message_task UNIQUE (background_task_id),
    ADD CONSTRAINT fk_chat_message_task FOREIGN KEY (background_task_id)
        REFERENCES background_task (id);
