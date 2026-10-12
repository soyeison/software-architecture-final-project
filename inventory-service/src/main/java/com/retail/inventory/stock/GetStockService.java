package com.retail.inventory.stock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: consultar el stock actual de un SKU. */
@Service
public class GetStockService {

    private final StockRepository stockRepository;

    public GetStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional(readOnly = true)
    public Stock getBySku(String sku) {
        return stockRepository.findById(sku).orElseThrow(() -> new SkuNotFoundException(sku));
    }
}
