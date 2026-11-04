package com.smartdrive.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileRequest(@NotBlank @Size(max = 64) String displayName) {}
