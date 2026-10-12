#!/usr/bin/env bash
# Escenario 10 — Seguridad (spec global §4.2): sin token o con token inválido, 401; con un token sin permiso
# de venta, 403. Las consultas del Catálogo son públicas. Uso: e2e/10-security.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="TENIS-RUNNER-42"

require_stack
require_synced "$SKU"
stock_before="$(inventory_stock "$SKU")"

step "Venta sin token"
sell "$SKU" POS ""
check "sin token responde 401" "$SELL_STATUS" "401"

step "Venta con un token inválido"
sell "$SKU" POS "esto.no.es-un-jwt"
check "token inválido responde 401" "$SELL_STATUS" "401"

step "Venta con un token sin permiso de venta"
sell "$SKU" POS "$(token no-scope)"
check "token sin permiso responde 403" "$SELL_STATUS" "403"

step "Venta con un token de solo lectura (operador)"
sell "$SKU" POS "$(token operator)"
check "token de operador responde 403" "$SELL_STATUS" "403"

step "Comprobando que el stock no cambió y que el Catálogo es público"
check "el stock en Inventario no cambió" "$(inventory_stock "$SKU")" "$stock_before"
check "la consulta del Catálogo sin token responde 200" "$(http_status "$CATALOG_URL/api/v1/catalog/products/$SKU")" "200"

finish "10 Seguridad"
