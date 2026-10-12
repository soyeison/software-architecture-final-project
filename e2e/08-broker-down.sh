#!/usr/bin/env bash
# Escenario 8 — RabbitMQ caído (spec global §4.2): la venta se confirma y el evento queda PENDING en el outbox; al
# volver RabbitMQ el publicador lo envía y el Catálogo converge, sin perder ningún evento. Detiene un contenedor:
# el trap de salida lo vuelve a levantar aunque el escenario falle o se interrumpa.
# Mide el tiempo hasta vaciar el outbox y hasta que el Catálogo converge, desde que se reinicia RabbitMQ.
# Uso: e2e/08-broker-down.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="BOTELLA-TERMICA-750"
SALES=2
# La reconexión de los servicios tras volver el broker puede tardar (medido ~6 s; se observaron hasta ~25 s en otra ocasión).
RECOVERY_TIMEOUT_SECONDS=90

require_stack
require_synced "$SKU"
require_stock "$SKU" "$SALES"
install_restore_trap
before="$(catalog_stock "$SKU")"

step "Detiene RabbitMQ"
stop_service rabbitmq
check "RabbitMQ se detuvo" "$?" "0"

step "Registra $SALES ventas de $SKU con RabbitMQ caído"
sale_ids=()
for index in $(seq 1 "$SALES"); do
  channel="POS"
  (( index % 2 == 0 )) && channel="ONLINE"
  sell "$SKU" "$channel"
  check "la venta $index ($channel) responde 201: Inventario sigue vendiendo" "$SELL_STATUS" "201"
  sale_ids+=("$(jq -r '.saleId' <<<"$SELL_BODY")")
done

step "Comprobando que los eventos quedan pendientes en el outbox"
wait_until "el outbox conserva los $SALES eventos como PENDING" outbox_count_with_status_is PENDING "$SALES" "${sale_ids[@]}"
check "el Catálogo no cambió" "$(catalog_stock "$SKU")" "$before"
check "el Catálogo sigue sano sin RabbitMQ" "$(http_status "$CATALOG_URL/actuator/health")" "200"

step "Reinicia RabbitMQ"
restart_ms="$(now_ms)"
start_service rabbitmq
check "RabbitMQ vuelve a estar sano" "$?" "0"
drain_ms=""
if WAIT_TIMEOUT_SECONDS="$RECOVERY_TIMEOUT_SECONDS" wait_until "el outbox publica los $SALES eventos (PUBLISHED)" \
  outbox_count_with_status_is PUBLISHED "$SALES" "${sale_ids[@]}"; then
  drain_ms=$(( $(now_ms) - restart_ms ))
fi
if WAIT_TIMEOUT_SECONDS="$RECOVERY_TIMEOUT_SECONDS" wait_until "el Catálogo converge a stock $((before - SALES))" \
  catalog_stock_is "$SKU" "$((before - SALES))"; then
  catch_up_ms=$(( $(now_ms) - restart_ms ))
  if [[ -n "$drain_ms" ]]; then
    metric outbox_drain_ms "$drain_ms"
    metric broker_recovery_catch_up_ms "$catch_up_ms"
    echo "INFO: outbox vaciado ${drain_ms} ms y Catálogo al día ${catch_up_ms} ms después de reiniciar RabbitMQ"
  fi
fi

step "Comprobando que no se perdió ningún evento"
check "no quedan eventos PENDING de estas ventas" "$(outbox_count_with_status PENDING "${sale_ids[@]}")" "0"
check "el Catálogo aplicó los $SALES eventos, en orden" \
  "$(catalog_applied_sequence "$SKU" "${sale_ids[@]}" | paste -sd' ')" "${sale_ids[*]}"
wait_until "la cola $QUEUE queda vacía" queue_is_empty "$QUEUE"

finish "08 RabbitMQ caído"
