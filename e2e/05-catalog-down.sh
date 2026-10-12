#!/usr/bin/env bash
# Escenario 5 — Catálogo caído (spec global §4.2): con el Catálogo detenido los eventos se acumulan en la cola
# durable; al reiniciarlo se procesan todos, en orden, y el stock converge. Detiene un contenedor: el trap de
# salida lo vuelve a levantar aunque el escenario falle o se interrumpa.
# Mide la puesta al día del Catálogo (desde que se reinicia hasta que converge).
# Uso: e2e/05-catalog-down.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="MOCHILA-TRAIL-20L"
SALES=3

require_stack
require_synced "$SKU"
require_stock "$SKU" "$SALES"
install_restore_trap
before="$(catalog_stock "$SKU")"

step "Detiene el Catálogo"
stop_service catalog-service
check "el Catálogo se detuvo" "$?" "0"
wait_until "la cola $QUEUE se queda sin consumidores" queue_consumers_is "$QUEUE" "0"

step "Registra $SALES ventas de $SKU mientras el Catálogo está caído"
sale_ids=()
for index in $(seq 1 "$SALES"); do
  channel="POS"
  (( index % 2 == 0 )) && channel="ONLINE"
  sell "$SKU" "$channel"
  check "la venta $index ($channel) responde 201" "$SELL_STATUS" "201"
  sale_ids+=("$(jq -r '.saleId' <<<"$SELL_BODY")")
done
wait_until "la cola acumula los $SALES mensajes" queue_messages_is "$QUEUE" "$SALES"

step "Reinicia el Catálogo"
restart_ms="$(now_ms)"
start_service catalog-service
check "el Catálogo vuelve a estar sano" "$?" "0"
if wait_until "el Catálogo converge a stock $((before - SALES))" catalog_stock_is "$SKU" "$((before - SALES))"; then
  catch_up_ms=$(( $(now_ms) - restart_ms ))
  metric catalog_catch_up_ms "$catch_up_ms"
  echo "INFO: puesta al día del Catálogo: ${catch_up_ms} ms (incluye el arranque del servicio)"
fi

step "Comprobando que no se perdió ni se reordenó nada"
check "los eventos se aplicaron en el orden de las ventas" \
  "$(catalog_applied_sequence "$SKU" "${sale_ids[@]}" | paste -sd' ')" "${sale_ids[*]}"
wait_until "la cola $QUEUE queda vacía" queue_is_empty "$QUEUE"
wait_until "el Catálogo coincide con Inventario" synced "$SKU"

finish "05 Catálogo caído"
