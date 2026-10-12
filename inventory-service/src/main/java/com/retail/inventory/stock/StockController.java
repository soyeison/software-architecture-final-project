package com.retail.inventory.stock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {

    private final GetStockService getStockService;

    public StockController(GetStockService getStockService) {
        this.getStockService = getStockService;
    }

    @GetMapping("/{sku}")
    public StockResponse getStock(@PathVariable String sku) {
        return StockResponse.from(getStockService.getBySku(sku));
    }
}
