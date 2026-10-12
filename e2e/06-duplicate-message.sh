#!/usr/bin/env bash
# Escenario 6 — Mensaje duplicado (spec global §4.2): el eventId ya registrado se reconoce y se confirma sin
# cambios. Inyecta eventos de CALCETINES-PACK-3 directamente en RabbitMQ (su estado en el Catálogo diverge del
# de Inventario por diseño, ver docs/seed-data.md). Uso: e2e/06-duplicate-message.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="CALCETINES-PACK-3"
STOCK=15

require_stack
dlq_before="$(queue_messages "$DEAD_LETTER_QUEUE")"
[[ "$(is_integer "$dlq_before")" == "yes" ]] || fail_precondition "no se pudo leer la cola $DEAD_LETTER_QUEUE"
base="$(version_base)"
event_id="$(new_uuid)"
applied_log="event_applied sku=$SKU eventId=$event_id"
duplicate_log="event_duplicate sku=$SKU eventId=$event_id"

step "Inyecta un evento nuevo de $SKU (stock $STOCK, versión $base)"
publish_event "$SKU" "$STOCK" "$base" "$event_id"
check "el broker enruta el evento a la cola" "$?" "0"
wait_until "el Catálogo aplica el evento (log event_applied)" catalog_log_count_is "$applied_log" 1
check "el Catálogo muestra stock $STOCK" "$(catalog_stock "$SKU")" "$STOCK"

step "Reenvía exactamente el mismo mensaje"
publish_event "$SKU" "$STOCK" "$base" "$event_id"
check "el broker enruta el duplicado a la cola" "$?" "0"
wait_until "el Catálogo reconoce el duplicado (log event_duplicate)" catalog_log_count_is "$duplicate_log" 1

step "Reenvía el mismo eventId con otra versión y otro stock: debe descartarse por eventId, no por versión"
publish_event "$SKU" 3 "$((base + 1))" "$event_id"
check "el broker enruta el mensaje a la cola" "$?" "0"
wait_until "el Catálogo vuelve a reconocer el duplicado" catalog_log_count_is "$duplicate_log" 2

step "Comprobando que no cambió nada"
check "el stock sigue siendo el del primer evento" "$(catalog_stock "$SKU")" "$STOCK"
check "el evento se aplicó una sola vez" "$(catalog_log_count "$applied_log")" "1"
wait_until "la cola $QUEUE queda vacía" queue_is_empty "$QUEUE"
check "la DLQ no recibió mensajes" "$(queue_messages "$DEAD_LETTER_QUEUE")" "$dlq_before"

finish "06 Mensaje duplicado"
