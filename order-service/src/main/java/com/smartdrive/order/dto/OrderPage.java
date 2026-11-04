package com.smartdrive.order.dto;

import java.util.List;

public record OrderPage(int page, int size, long total, List<OrderView> items) {
}
