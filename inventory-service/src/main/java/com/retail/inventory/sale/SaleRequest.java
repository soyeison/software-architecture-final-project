package com.retail.inventory.sale;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.retail.inventory.stock.Channel;

/** Cuerpo de {@code POST /api/v1/inventory/{sku}/sales} (schema {@code SaleRequest} del contrato). */
public record SaleRequest(@NotNull @Min(1) Integer quantity, @NotNull Channel channel) {
}
