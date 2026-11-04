-- Day 8 订单库；结构以项目开发文档第 5 节为基线，后续 Day 9-12 继续使用预留表。
-- 使用已有项目数据库账号执行；不包含凭据或演示数据。
CREATE DATABASE IF NOT EXISTS order_db CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS order_db.ride_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id CHAR(36) NOT NULL,
  passenger_id BIGINT NOT NULL,
  driver_id BIGINT NULL,
  pickup_name VARCHAR(100) NOT NULL,
  pickup_lng DECIMAL(10,6) NOT NULL,
  pickup_lat DECIMAL(9,6) NOT NULL,
  dropoff_name VARCHAR(100) NOT NULL,
  dropoff_lng DECIMAL(10,6) NOT NULL,
  dropoff_lat DECIMAL(9,6) NOT NULL,
  amount DECIMAL(10,2) NOT NULL DEFAULT 50.00,
  status VARCHAR(20) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_passenger_request (passenger_id, request_id),
  KEY idx_passenger_created (passenger_id, created_at),
  KEY idx_driver_created (driver_id, created_at),
  KEY idx_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS order_db.driver_active_order (
  driver_id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL UNIQUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS order_db.outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL UNIQUE,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  KEY idx_status_created (status, created_at)
);
