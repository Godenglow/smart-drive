package com.smartdrive.driver.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LocationRequest(
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        @NotNull @DecimalMin("-85.05112878") @DecimalMax("85.05112878") BigDecimal lat) {}
