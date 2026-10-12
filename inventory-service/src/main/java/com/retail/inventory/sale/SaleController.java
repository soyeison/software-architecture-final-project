package com.retail.inventory.sale;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.retail.inventory.stock.StockChange;

@RestController
@RequestMapping("/api/v1/inventory")
public class SaleController {

    private final RegisterSaleService registerSaleService;

    public SaleController(RegisterSaleService registerSaleService) {
        this.registerSaleService = registerSaleService;
    }

    @PostMapping("/{sku}/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse registerSale(@PathVariable String sku, @Valid @RequestBody SaleRequest request) {
        StockChange change = registerSaleService.register(sku, request.quantity(), request.channel());
        return SaleResponse.from(request, change);
    }
}
