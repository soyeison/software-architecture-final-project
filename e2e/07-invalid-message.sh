#!/usr/bin/env bash
# Escenario 7 — Mensaje inválido (spec global §4.2): un mensaje que nunca podrá procesarse se rechaza sin
# reencolar, termina en la DLQ y no bloquea la cola. Inyecta JSON corrupto, un evento sin campo obligatorio y
# un evento de un SKU desconocido; después hace una venta real. Uso: e2e/07-invalid-message.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
e2e_init "$@"
SKU="TENIS-RUNNER-42"

require_stack
require_synced "$SKU"
require_stock "$SKU" 1
dlq_before="$(queue_messages "$DEAD_LETTER_QUEUE")"
[[ "$(is_integer "$dlq_before")" == "yes" ]] || fail_precondition "no se pudo leer la cola $DEAD_LETTER_QUEUE"
stock_before="$(catalog_stock "$SKU")"
base="$(version_base)"

step "Inyecta un mensaje con JSON corrupto"
publish_raw '{ esto no es json'
check "el broker enruta el mensaje corrupto" "$?" "0"

step "Inyecta un evento sin el campo obligatorio newStock"
publish_raw "$(build_event "$(new_uuid)" "$SKU" 1 1 | jq -c 'del(.newStock)')"
check "el broker enruta el evento incompleto" "$?" "0"

step "Inyecta un evento de un SKU que el Catálogo no conoce"
publish_event "SKU-INEXISTENTE-E2E" 3 "$base"
check "el broker enruta el evento de SKU desconocido" "$?" "0"

step "Esperando que los tres mensajes lleguen a la DLQ"
wait_until "la DLQ recibe los 3 mensajes" queue_messages_is "$DEAD_LETTER_QUEUE" "$((dlq_before + 3))"
wait_until "la cola $QUEUE queda vacía (nada reencolado)" queue_is_empty "$QUEUE"
check "el stock de $SKU en el Catálogo no cambió" "$(catalog_stock "$SKU")" "$stock_before"

step "Una venta real posterior debe llegar al Catálogo: la cola no se bloqueó"
sell "$SKU" POS
check "la venta responde 201" "$SELL_STATUS" "201"
wait_until "el Catálogo muestra stock $((stock_before - 1))" catalog_stock_is "$SKU" "$((stock_before - 1))"
check "la DLQ sigue con solo los 3 mensajes de este escenario" "$(queue_messages "$DEAD_LETTER_QUEUE")" "$((dlq_before + 3))"

finish "07 Mensaje inválido"
