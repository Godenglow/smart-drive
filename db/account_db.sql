-- account_db 初始化脚本（规格文档 §5 数据设计，Day 4 执行）
-- 执行方式：mysql -u root -p --default-character-set=utf8mb4 < db/account_db.sql
-- 说明：数据库与表结构只在此维护；不包含任何账号与密码，项目专用数据库账号由本机私有配置创建。

CREATE DATABASE IF NOT EXISTS account_db CHARACTER SET utf8mb4;

CREATE TABLE account_db.account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
