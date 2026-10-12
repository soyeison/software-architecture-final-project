package com.retail.catalog.product;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: consultar productos y su disponibilidad. */
@Service
public class ProductQueryService {

    private final ProductRepository productRepository;

    public ProductQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Todos los productos, ordenados por SKU (sin paginación, como dice el contrato). */
    @Transactional(readOnly = true)
    public List<Product> list() {
        return productRepository.findAll(Sort.by("sku"));
    }

    @Transactional(readOnly = true)
    public Product getBySku(String sku) {
        return productRepository.findById(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    }
}
