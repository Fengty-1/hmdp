CREATE TABLE IF NOT EXISTS account_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    phone VARCHAR(11) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'STUDENT',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_account_user_phone UNIQUE (phone),
    CONSTRAINT ck_account_user_role CHECK (role IN ('STUDENT', 'ORGANIZER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS activity_location (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organizer_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    address VARCHAR(255) NOT NULL,
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL,
    CONSTRAINT fk_location_organizer FOREIGN KEY (organizer_id) REFERENCES account_user(id),
    CONSTRAINT ck_location_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_location_latitude CHECK (latitude BETWEEN -85.05112878 AND 85.05112878)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS activity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organizer_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    cover_image VARCHAR(500),
    CONSTRAINT fk_activity_organizer FOREIGN KEY (organizer_id) REFERENCES account_user(id),
    CONSTRAINT fk_activity_location FOREIGN KEY (location_id) REFERENCES activity_location(id),
    INDEX idx_activity_location (location_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS activity_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    capacity INT NOT NULL,
    remaining_capacity INT NOT NULL,
    registration_start_at TIMESTAMP(3) NOT NULL,
    registration_end_at TIMESTAMP(3) NOT NULL,
    start_at TIMESTAMP(3) NOT NULL,
    end_at TIMESTAMP(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    runtime_expire_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_session_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
    CONSTRAINT ck_session_capacity CHECK (capacity > 0 AND remaining_capacity BETWEEN 0 AND capacity),
    CONSTRAINT ck_session_time CHECK (registration_start_at < registration_end_at
        AND registration_end_at <= start_at AND start_at < end_at),
    CONSTRAINT ck_session_status CHECK ((status = 'DRAFT' AND runtime_expire_at IS NULL)
        OR (status = 'PUBLISHED' AND runtime_expire_at > end_at)),
    INDEX idx_session_activity_status (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS registration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_registration_request UNIQUE (request_id),
    CONSTRAINT uk_registration_user_session UNIQUE (user_id, session_id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES account_user(id),
    CONSTRAINT fk_registration_session FOREIGN KEY (session_id) REFERENCES activity_session(id),
    INDEX idx_registration_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS registration_result (
    request_id VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    registration_id BIGINT NULL,
    failure_code VARCHAR(64) NULL,
    completed_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_result_user FOREIGN KEY (user_id) REFERENCES account_user(id),
    CONSTRAINT fk_result_session FOREIGN KEY (session_id) REFERENCES activity_session(id),
    CONSTRAINT fk_result_registration FOREIGN KEY (registration_id) REFERENCES registration(id),
    CONSTRAINT ck_result_terminal CHECK (
        (status = 'SUCCESS' AND registration_id IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND registration_id IS NULL AND failure_code IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS participation_credential (
    registration_id BIGINT NOT NULL PRIMARY KEY,
    code CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT uk_participation_credential_code UNIQUE (code),
    CONSTRAINT fk_credential_registration FOREIGN KEY (registration_id) REFERENCES registration(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS participation_record (
    registration_id BIGINT NOT NULL PRIMARY KEY,
    checked_by BIGINT NOT NULL,
    checked_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_participation_credential FOREIGN KEY (registration_id) REFERENCES participation_credential(registration_id),
    CONSTRAINT fk_participation_organizer FOREIGN KEY (checked_by) REFERENCES account_user(id),
    INDEX idx_participation_time (checked_at, registration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
