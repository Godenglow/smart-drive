package com.smartdrive.driver.dto;

import java.time.Instant;

public record OnlineView(boolean online, Instant updatedAt) {}
