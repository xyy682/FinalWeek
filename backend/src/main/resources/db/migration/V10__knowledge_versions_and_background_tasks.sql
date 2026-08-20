RENAME TABLE parse_task TO background_task;

ALTER TABLE background_task
    ADD COLUMN business_id BINARY(16) NULL AFTER material_id,
    ADD COLUMN visible_in_global_drawer BOOLEAN NOT NULL DEFAULT TRUE AFTER task_type;

UPDATE background_task
SET business_id = COALESCE(material_id, course_id);

ALTER TABLE background_task
    MODIFY COLUMN business_id BINARY(16) NOT NULL;

CREATE INDEX idx_background_task_active_drawer
    ON background_task (user_id, visible_in_global_drawer, status, updated_at);

CREATE TABLE course_knowledge_version (
    id BINARY(16) NOT NULL,
    course_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    material_set_hash CHAR(64) NOT NULL,
    outline_id BINARY(16) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    error_code VARCHAR(80) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_version_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT uk_knowledge_version_number UNIQUE (course_id, version),
    CONSTRAINT uk_knowledge_version_material_set UNIQUE (course_id, material_set_hash),
    INDEX idx_knowledge_version_course_status (course_id, status, version)
);

CREATE TABLE course_knowledge_version_material (
    knowledge_version_id BINARY(16) NOT NULL,
    material_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (knowledge_version_id, material_id),
    CONSTRAINT fk_knowledge_version_material_version FOREIGN KEY (knowledge_version_id)
        REFERENCES course_knowledge_version (id),
    CONSTRAINT fk_knowledge_version_material_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT uk_knowledge_version_material_position UNIQUE (knowledge_version_id, position)
);

INSERT INTO course_knowledge_version (
    id, course_id, version, status, material_set_hash, created_at, published_at
)
SELECT UUID_TO_BIN(UUID()), outline.course_id, outline.generation_version, 'PUBLISHED',
       SHA2(COALESCE(GROUP_CONCAT(CONCAT(HEX(material.id), ':', COALESCE(material.content_hash, ''))
            ORDER BY HEX(material.id) SEPARATOR '|'), ''), 256),
       outline.generated_at, outline.generated_at
FROM outline
LEFT JOIN material ON material.course_id = outline.course_id
    AND material.deleted = FALSE AND material.status = 'SUCCEEDED'
GROUP BY outline.course_id, outline.generation_version, outline.generated_at;

INSERT INTO course_knowledge_version (
    id, course_id, version, status, material_set_hash, created_at, published_at, error_code
)
SELECT UUID_TO_BIN(UUID()), task.course_id, task.generation_version,
       CASE WHEN task.status IN ('FAILED', 'CANCELLED') THEN 'FAILED' ELSE 'GENERATING' END,
       SHA2(CONCAT(COALESCE(GROUP_CONCAT(CONCAT(HEX(material.id), ':', COALESCE(material.content_hash, ''))
            ORDER BY HEX(material.id) SEPARATOR '|'), ''), ':legacy:', HEX(task.id)), 256),
       task.created_at, NULL, task.error_code
FROM background_task task
LEFT JOIN material ON material.course_id = task.course_id
    AND material.deleted = FALSE AND material.status = 'SUCCEEDED'
WHERE task.task_type = 'GENERATE_OUTLINE' AND task.generation_version IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM course_knowledge_version existing
      WHERE existing.course_id = task.course_id AND existing.version = task.generation_version
  )
GROUP BY task.id, task.course_id, task.generation_version, task.status, task.created_at, task.error_code;

INSERT INTO course_knowledge_version_material (knowledge_version_id, material_id, position)
SELECT version.id, material.id,
       ROW_NUMBER() OVER (PARTITION BY version.id ORDER BY HEX(material.id)) - 1
FROM course_knowledge_version version
JOIN material ON material.course_id = version.course_id
    AND material.deleted = FALSE AND material.status = 'SUCCEEDED';

UPDATE background_task task
JOIN course_knowledge_version version
    ON version.course_id = task.course_id AND version.version = task.generation_version
SET task.business_id = version.id
WHERE task.task_type = 'GENERATE_OUTLINE';

ALTER TABLE outline
    ADD INDEX idx_outline_course (course_id);

ALTER TABLE outline
    DROP INDEX uk_outline_course,
    ADD COLUMN knowledge_version_id BINARY(16) NULL AFTER course_id;

UPDATE outline
JOIN course_knowledge_version version
    ON version.course_id = outline.course_id AND version.version = outline.generation_version
SET outline.knowledge_version_id = version.id;

ALTER TABLE outline
    MODIFY COLUMN knowledge_version_id BINARY(16) NOT NULL,
    ADD CONSTRAINT fk_outline_knowledge_version FOREIGN KEY (knowledge_version_id)
        REFERENCES course_knowledge_version (id),
    ADD CONSTRAINT uk_outline_knowledge_version UNIQUE (knowledge_version_id);

UPDATE course_knowledge_version version
JOIN outline ON outline.knowledge_version_id = version.id
SET version.outline_id = outline.id;

ALTER TABLE course_knowledge_version
    ADD CONSTRAINT fk_knowledge_version_outline FOREIGN KEY (outline_id) REFERENCES outline (id);

ALTER TABLE course
    ADD COLUMN current_knowledge_version_id BINARY(16) NULL AFTER outline_generation_seq,
    ADD CONSTRAINT fk_course_current_knowledge_version FOREIGN KEY (current_knowledge_version_id)
        REFERENCES course_knowledge_version (id);

UPDATE course
JOIN course_knowledge_version version
    ON version.course_id = course.id AND version.status = 'PUBLISHED'
SET course.current_knowledge_version_id = version.id;
