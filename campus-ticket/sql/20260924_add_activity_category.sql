-- 在 campus_ticket 数据库执行一次；已有活动归为 OTHER。
ALTER TABLE activity ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'OTHER' AFTER title;
