package com.smartdrive.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateOrderRequest(@NotNull UUID requestId,
                                 @NotNull @Valid Place pickup,
                                 @NotNull @Valid Place dropoff) {
}
