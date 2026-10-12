#!/usr/bin/env bash
# Escenario 4 — Venta sin stock (spec global §4.2): 409, sin cambios de stock y sin evento.
# Uso: e2e/04-out-of-stock.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="CAMISETA-DRYFIT-M"

require_stack
require_synced "$SKU"
stock="$(inventory_stock "$SKU")"
[[ "$stock" == "0" ]] || fail_precondition "$SKU tiene stock $stock y el escenario necesita 0"
events_before="$(outbox_count "$SKU")"

step "Venta de 1 unidad de $SKU, que no tiene stock"
sell "$SKU" POS
check "la venta responde 409" "$SELL_STATUS" "409"
check "el rechazo es por stock insuficiente" "$(jq -r '.type | endswith("insufficient-stock")' <<<"$SELL_BODY")" "true"

step "Comprobando que no cambió nada"
check "el stock en Inventario sigue en 0" "$(inventory_stock "$SKU")" "0"
check "no se generó ningún evento" "$(outbox_count "$SKU")" "$events_before"
check "el Catálogo sigue mostrando stock 0" "$(catalog_stock "$SKU")" "0"

finish "04 Venta sin stock"
