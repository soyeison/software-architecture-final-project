-- Datos semilla de docs/seed-data.md (los mismos SKU que siembra el Catálogo).
INSERT INTO stock (sku, quantity, version, updated_at) VALUES
    ('TENIS-RUNNER-42',      5, 1, date_trunc('milliseconds', now())),
    ('BALON-FUTBOL-5',       1, 1, date_trunc('milliseconds', now())),
    ('CAMISETA-DRYFIT-M',    0, 1, date_trunc('milliseconds', now())),
    ('MOCHILA-TRAIL-20L',   10, 1, date_trunc('milliseconds', now())),
    ('BOTELLA-TERMICA-750', 10, 1, date_trunc('milliseconds', now())),
    ('GORRA-SPORT-NEGRA',   50, 1, date_trunc('milliseconds', now())),
    ('CALCETINES-PACK-3',   20, 1, date_trunc('milliseconds', now()));
