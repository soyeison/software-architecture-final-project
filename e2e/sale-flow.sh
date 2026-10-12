#!/usr/bin/env bash
# Flujo común de los escenarios 1 y 2 (spec global §4.1): una venta se refleja en el Catálogo. Se carga con
# `source` desde 01-pos-sale.sh y 02-online-sale.sh.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# run_sale_scenario <nombre del escenario> <POS|ONLINE> [--demo]
run_sale_scenario() {
  local name="$1" channel="$2"
  shift 2
  e2e_init "$@"
  local sku="TENIS-RUNNER-42"

  require_stack
  require_synced "$sku"
  require_stock "$sku" 1
  local before expected sale_id latency
  before="$(catalog_stock "$sku")"
  expected=$((before - 1))

  step "Venta de 1 unidad de $sku por el canal $channel (stock actual: $before)"
  sell "$sku" "$channel"
  check "la venta $channel responde 201" "$SELL_STATUS" "201"
  check "Inventario informa el stock restante $expected" "$(jq -r '.remainingStock' <<<"$SELL_BODY")" "$expected"
  sale_id="$(jq -r '.saleId' <<<"$SELL_BODY")"

  step "Esperando que el Catálogo refleje la venta"
  wait_until "el Catálogo muestra stock $expected" catalog_stock_is "$sku" "$expected"
  latency="$(catalog_field "$sku" '.lastEventLatencyMs')"
  check "lastEventLatencyMs se informa" "$(is_integer "$latency")" "yes"
  echo "INFO: latencia venta -> Catálogo: ${latency} ms"

  step "Comprobando el outbox de Inventario y la cola del Catálogo"
  wait_until "el evento de la venta figura PUBLISHED en el outbox" outbox_event_published "$sale_id"
  wait_until "la cola $QUEUE queda vacía" queue_is_empty "$QUEUE"

  finish "$name"
}
