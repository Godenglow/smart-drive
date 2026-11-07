-- Step 6: driver-service 资料库；账号与密码通过本机私有配置提供。
CREATE DATABASE IF NOT EXISTS driver_db CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS driver_db.driver_profile (
  user_id BIGINT PRIMARY KEY,
  display_name VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- Step 10/13: MQ 消费者写入的候选资格；重复通知不覆盖已有状态。
CREATE TABLE IF NOT EXISTS driver_db.order_notice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  driver_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'NEW',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_order_driver (order_id, driver_id),
  KEY idx_driver_status (driver_id, status, created_at)
);

-- Step 13: 与该单全部通知同事务提交，重投时不再重新匹配其他司机。
CREATE TABLE IF NOT EXISTS driver_db.processed_order_event (
  order_id BIGINT PRIMARY KEY,
  event_id VARCHAR(36) NOT NULL,
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_processed_event (event_id)
);
