-- Run once in campus_ticket. Existing activity/registration rows are preserved.
CREATE TABLE booking_inventory (
    activity_id BIGINT NOT NULL PRIMARY KEY,
    epoch BIGINT NOT NULL DEFAULT 0,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE booking_order (
    order_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    registration_id BIGINT DEFAULT NULL,
    accepted_at DATETIME(3) DEFAULT NULL,
    expires_at DATETIME(3) NOT NULL,
    failure_code VARCHAR(64) DEFAULT NULL,
    consume_failures INT NOT NULL DEFAULT 0,
    redis_dirty BOOLEAN NOT NULL DEFAULT FALSE,
    next_check_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_booking_request (user_id, request_key),
    KEY idx_booking_activity (activity_id, status),
    KEY idx_booking_check (status, next_check_time),
    KEY idx_booking_dirty (redis_dirty, next_check_time),
    KEY idx_booking_registration (registration_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE booking_outbox (
    order_id VARCHAR(36) NOT NULL PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(500) DEFAULT NULL,
    KEY idx_booking_outbox_due (next_attempt_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE booking_order_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(500) DEFAULT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_booking_log (order_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
