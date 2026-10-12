#!/usr/bin/env bash
# Escenario 3 — Carrera por la última unidad (spec global §4.2): con stock 1, POS y online venden a la vez;
# una obtiene 201, la otra 409, y se genera un solo evento. Requiere BALON-FUTBOL-5 con stock exactamente 1,
# así que solo corre una vez por reinicio (e2e/reset.sh). Uso: e2e/03-last-unit-race.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="BALON-FUTBOL-5"

require_stack
require_synced "$SKU"
stock="$(inventory_stock "$SKU")"
[[ "$stock" == "1" ]] || fail_precondition "$SKU tiene stock $stock y el escenario necesita exactamente 1"
events_before="$(outbox_count "$SKU")"
[[ "$(is_integer "$events_before")" == "yes" ]] || fail_precondition "no se pudo leer el outbox de Inventario"

results="$(mktemp -d)"
trap 'rm -rf "$results"' EXIT
pos_token="$(token pos)"
online_token="$(token online)"

step "POS y online venden la última unidad de $SKU al mismo tiempo"
( sell "$SKU" POS "$pos_token";       echo "$SELL_STATUS" >"$results/pos.status";    echo "$SELL_BODY" >"$results/pos.body" ) &
( sell "$SKU" ONLINE "$online_token"; echo "$SELL_STATUS" >"$results/online.status"; echo "$SELL_BODY" >"$results/online.body" ) &
wait

statuses="$(cat "$results/pos.status" "$results/online.status" | sort -n | paste -sd' ')"
check "una venta responde 201 y la otra 409" "$statuses" "201 409"
loser_body="$results/online.body"
[[ "$(cat "$results/pos.status")" == "409" ]] && loser_body="$results/pos.body"
check "el rechazo es por stock insuficiente" "$(jq -r '.type | endswith("insufficient-stock")' "$loser_body")" "true"
check "el stock en Inventario es 0" "$(inventory_stock "$SKU")" "0"

step "Comprobando que se generó un solo evento y que el Catálogo converge"
wait_until "el outbox tiene exactamente un evento nuevo de $SKU" outbox_count_is "$SKU" "$((events_before + 1))"
wait_until "el Catálogo muestra stock 0" catalog_stock_is "$SKU" 0
check "el Catálogo marca el producto sin disponibilidad" "$(catalog_field "$SKU" '.inStock')" "false"

finish "03 Carrera por la última unidad"
