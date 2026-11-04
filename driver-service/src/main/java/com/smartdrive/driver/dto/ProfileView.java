package com.smartdrive.driver.dto;

import java.time.LocalDateTime;

public record ProfileView(long userId, String displayName, LocalDateTime createdAt) {}
