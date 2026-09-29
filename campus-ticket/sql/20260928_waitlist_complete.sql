-- MySQL 8. Existing rows are preserved. Run before restarting the application.
CREATE TABLE IF NOT EXISTS activity_waitlist (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, activity_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
 active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('WAITING','OFFERED') THEN 1 ELSE NULL END) STORED,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_waitlist_active(activity_id,user_id,active_flag),
 KEY idx_waitlist_dispatch(activity_id,status,id), KEY idx_waitlist_user(user_id,id),
 CONSTRAINT fk_waitlist_activity FOREIGN KEY(activity_id) REFERENCES activity(id),
 CONSTRAINT fk_waitlist_user FOREIGN KEY(user_id) REFERENCES campus_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS waitlist_quota (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, activity_id BIGINT NOT NULL, source_order_id VARCHAR(36) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'HELD', current_offer_id BIGINT NULL, version BIGINT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_quota_source_order(source_order_id), UNIQUE KEY uk_quota_current_offer(current_offer_id),
 KEY idx_quota_activity_status(activity_id,status,id), KEY idx_quota_recovery(status,update_time,id),
 CONSTRAINT fk_waitlist_quota_activity FOREIGN KEY(activity_id) REFERENCES activity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS waitlist_offer (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, waitlist_id BIGINT NOT NULL, quota_id BIGINT NOT NULL,
 source_order_id VARCHAR(36) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
 confirm_deadline DATETIME(3) NOT NULL, confirmed_at DATETIME(3) NULL, closed_at DATETIME(3) NULL,
 close_reason VARCHAR(50) NULL, confirmed_order_id VARCHAR(36) NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_offer_waitlist(waitlist_id), UNIQUE KEY uk_offer_confirmed_order(confirmed_order_id),
 KEY idx_offer_timeout(status,confirm_deadline,id), KEY idx_offer_source_order(source_order_id),
 KEY idx_offer_quota(quota_id,id),
 CONSTRAINT fk_offer_waitlist FOREIGN KEY(waitlist_id) REFERENCES activity_waitlist(id),
 CONSTRAINT fk_offer_quota FOREIGN KEY(quota_id) REFERENCES waitlist_quota(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS waitlist_redis_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, quota_id BIGINT NOT NULL, quota_version BIGINT NOT NULL,
 operation_type VARCHAR(32) NOT NULL, payload JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, next_attempt_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_task_quota_version(quota_id,quota_version),
 KEY idx_task_due(status,next_attempt_time,id), KEY idx_task_quota_status_version(quota_id,status,quota_version),
 CONSTRAINT fk_task_quota FOREIGN KEY(quota_id) REFERENCES waitlist_quota(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @waitlist_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='waitlist_redis_task' AND column_name='result_code')=0,
 'ALTER TABLE waitlist_redis_task ADD COLUMN result_code VARCHAR(32) NULL',
 'SELECT 1');
PREPARE waitlist_stmt FROM @waitlist_ddl;
EXECUTE waitlist_stmt;
DEALLOCATE PREPARE waitlist_stmt;
CREATE TABLE IF NOT EXISTS waitlist_notification (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,
 offer_id BIGINT NOT NULL, content VARCHAR(500) NOT NULL, read_time DATETIME(3) NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_notice_offer(offer_id), KEY idx_notice_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
