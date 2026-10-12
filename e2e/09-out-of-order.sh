#!/usr/bin/env bash
# Escenario 9 — Eventos fuera de orden (spec global §4.2): un evento con stockVersion menor o igual al guardado
# se ignora con ack; el estado final es el del más reciente. Inyecta la versión V+2 y luego la V+1 de
# CALCETINES-PACK-3 (su estado en el Catálogo diverge del de Inventario por diseño, ver docs/seed-data.md).
# Uso: e2e/09-out-of-order.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="CALCETINES-PACK-3"
NEWER_STOCK=12
OLDER_STOCK=14

require_stack
dlq_before="$(queue_messages "$DEAD_LETTER_QUEUE")"
[[ "$(is_integer "$dlq_before")" == "yes" ]] || fail_precondition "no se pudo leer la cola $DEAD_LETTER_QUEUE"
base="$(version_base)"
newer_id="$(new_uuid)"
older_id="$(new_uuid)"

step "Inyecta primero el evento más reciente (versión V+2, stock $NEWER_STOCK)"
publish_event "$SKU" "$NEWER_STOCK" "$((base + 2))" "$newer_id"
check "el broker enruta el evento reciente" "$?" "0"
wait_until "el Catálogo aplica el evento reciente (log event_applied)" \
  catalog_log_count_is "event_applied sku=$SKU eventId=$newer_id" 1
check "el Catálogo muestra stock $NEWER_STOCK" "$(catalog_stock "$SKU")" "$NEWER_STOCK"

step "Inyecta después el evento anterior (versión V+1, stock $OLDER_STOCK), que llega tarde"
publish_event "$SKU" "$OLDER_STOCK" "$((base + 1))" "$older_id"
check "el broker enruta el evento tardío" "$?" "0"
wait_until "el Catálogo descarta el evento tardío (log event_stale)" \
  catalog_log_count_is "event_stale sku=$SKU eventId=$older_id" 1

step "Comprobando que el estado final es el del evento más reciente"
check "el stock sigue siendo el del evento más reciente" "$(catalog_stock "$SKU")" "$NEWER_STOCK"
check "el evento tardío no se aplicó" "$(catalog_log_count "event_applied sku=$SKU eventId=$older_id")" "0"
wait_until "la cola $QUEUE queda vacía" queue_is_empty "$QUEUE"
check "la DLQ no recibió mensajes" "$(queue_messages "$DEAD_LETTER_QUEUE")" "$dlq_before"

finish "09 Eventos fuera de orden"
