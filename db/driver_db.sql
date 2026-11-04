-- Day 6: driver-service 资料库；账号与密码通过本机私有配置提供。
CREATE DATABASE IF NOT EXISTS driver_db CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS driver_db.driver_profile (
  user_id BIGINT PRIMARY KEY,
  display_name VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
