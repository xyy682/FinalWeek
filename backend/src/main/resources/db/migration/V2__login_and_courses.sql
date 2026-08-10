CREATE TABLE user_account (
    id BINARY(16) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_user_account_email UNIQUE (email)
);

CREATE TABLE course (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_course_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    INDEX idx_course_owner_active (user_id, deleted, updated_at)
);

