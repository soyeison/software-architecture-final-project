-- Datos semilla de docs/seed-data.md (los mismos SKU que siembra Inventario). La versión interna del
-- stock arranca en 0 para que cualquier primer evento (stockVersion >= 1) se aplique.
INSERT INTO products (sku, name, description, price, currency, available_stock, stock_version, updated_at,
                      last_event_latency_ms) VALUES
    ('TENIS-RUNNER-42',     'Tenis Runner talla 42',         'Tenis para correr, malla transpirable',                   289900, 'COP',  5, 0, date_trunc('milliseconds', now()), NULL),
    ('BALON-FUTBOL-5',      'Balón de fútbol n.º 5',         'Balón de fútbol de tamaño oficial, costuras reforzadas',  119900, 'COP',  1, 0, date_trunc('milliseconds', now()), NULL),
    ('CAMISETA-DRYFIT-M',   'Camiseta Dry-Fit talla M',      'Camiseta deportiva de secado rápido',                      79900, 'COP',  0, 0, date_trunc('milliseconds', now()), NULL),
    ('MOCHILA-TRAIL-20L',   'Mochila Trail 20 L',            'Mochila de 20 litros para senderismo, con espalda ventilada', 189900, 'COP', 10, 0, date_trunc('milliseconds', now()), NULL),
    ('BOTELLA-TERMICA-750', 'Botella térmica 750 ml',        'Botella de acero inoxidable que conserva la temperatura',  59900, 'COP', 10, 0, date_trunc('milliseconds', now()), NULL),
    ('GORRA-SPORT-NEGRA',   'Gorra Sport negra',             'Gorra deportiva negra con ajuste trasero',                 39900, 'COP', 50, 0, date_trunc('milliseconds', now()), NULL),
    ('CALCETINES-PACK-3',   'Calcetines deportivos pack x3', 'Pack de tres pares de calcetines deportivos',              24900, 'COP', 20, 0, date_trunc('milliseconds', now()), NULL);
