package com.smartdrive.order.entity;

public class OutboxEvent {
    public Long id;
    public String eventId;
    public long aggregateId;
    public String eventType;
    public String payload;
    public String status;
    public int attempts;
}
