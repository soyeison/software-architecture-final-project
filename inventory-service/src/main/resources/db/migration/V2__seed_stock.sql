-- Datos semilla de docs/seed-data.md (los mismos SKU que siembra el Catálogo).
INSERT INTO stock (sku, quantity, version, updated_at) VALUES
    ('TENIS-RUNNER-42',      5, 1, now()),
    ('BALON-FUTBOL-5',       1, 1, now()),
    ('CAMISETA-DRYFIT-M',    0, 1, now()),
    ('MOCHILA-TRAIL-20L',   10, 1, now()),
    ('BOTELLA-TERMICA-750', 10, 1, now()),
    ('GORRA-SPORT-NEGRA',   50, 1, now()),
    ('CALCETINES-PACK-3',   20, 1, now());
