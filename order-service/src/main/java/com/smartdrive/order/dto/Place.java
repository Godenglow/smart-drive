package com.smartdrive.order.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record Place(
        @NotBlank @Size(max = 100) String name,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        @NotNull @DecimalMin("-85.05112878") @DecimalMax("85.05112878") BigDecimal lat) {
}
