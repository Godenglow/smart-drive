package com.smartdrive.account.dto;

import java.time.Instant;

public record LoginResponse(String accessToken, Instant expiresAt, String role) {}
