-- MySQL 8；仅用于空数据库初始化，不要在已有库中用它替代增量迁移。
-- 先创建并选中 campus_ticket 数据库，再执行本文件。包含 category，无须再执行分类迁移。
CREATE TABLE campus_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_no VARCHAR(32) NOT NULL,
    name VARCHAR(50) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    password_hash VARCHAR(100) DEFAULT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    UNIQUE KEY uk_student_no (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE activity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    location VARCHAR(200) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    registration_start_time DATETIME NOT NULL,
    registration_end_time DATETIME NOT NULL,
    total_quota INT NOT NULL,
    remaining_quota INT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE registration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    cancel_time DATETIME DEFAULT NULL,
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_activity_id (activity_id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES campus_user(id),
    CONSTRAINT fk_registration_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
