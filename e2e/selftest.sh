#!/usr/bin/env bash
# Prueba de la lógica de lib.sh que tiene riesgo real (spec E2E §4). No requiere Docker.
# Uso: e2e/selftest.sh
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

failures=0
expect() { # expect <descripción> <obtenido> <esperado>
  if [[ "$2" == "$3" ]]; then echo "ok:   $1"; else echo "FALLA: $1 (obtenido: $2, esperado: $3)"; failures=$((failures + 1)); fi
}

# --- statistics ---
statistics 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20
expect "statistics: mínimo de 1..20" "$STAT_MIN" "1"
expect "statistics: promedio de 1..20" "$STAT_AVERAGE" "10.5"
expect "statistics: p95 de 1..20 (rango 19)" "$STAT_P95" "19"
expect "statistics: máximo de 1..20" "$STAT_MAX" "20"

statistics 900 120 480 5
expect "statistics: ordena valores desordenados (mínimo)" "$STAT_MIN" "5"
expect "statistics: ordena valores desordenados (máximo)" "$STAT_MAX" "900"
expect "statistics: p95 de 4 valores es el mayor" "$STAT_P95" "900"
expect "statistics: promedio de 4 valores" "$STAT_AVERAGE" "376.2"

statistics 42
expect "statistics: un solo valor (p95)" "$STAT_P95" "42"

statistics >/dev/null 2>&1
expect "statistics: sin valores devuelve 1" "$?" "1"

# --- is_integer ---
expect "is_integer acepta 386" "$(is_integer 386)" "yes"
expect "is_integer rechaza null" "$(is_integer null)" "no"
expect "is_integer rechaza vacío" "$(is_integer '')" "no"

# --- check ---
E2E_STATUS=0
check "descripción" "a" "a" >/dev/null
expect "check: igual no marca fallo" "$E2E_STATUS" "0"
check "descripción" "a" "b" >/dev/null
expect "check: distinto marca fallo" "$E2E_STATUS" "1"

# --- wait_until ---
E2E_STATUS=0
WAIT_TIMEOUT_SECONDS=2
wait_until "condición inmediata" true >/dev/null
expect "wait_until: éxito inmediato devuelve 0" "$?" "0"
expect "wait_until: éxito inmediato no marca fallo" "$E2E_STATUS" "0"

marker="$(mktemp -u)"
( sleep 0.6; touch "$marker" ) &
wait_until "condición que se cumple a los 0,6 s" test -e "$marker" >/dev/null
expect "wait_until: éxito tras esperar devuelve 0" "$?" "0"
expect "wait_until: registra al menos 500 ms esperados" "$([[ $WAITED_MS -ge 500 ]] && echo yes || echo no)" "yes"
rm -f "$marker"

wait_until "condición imposible" false >/dev/null
expect "wait_until: tiempo agotado devuelve 1" "$?" "1"
expect "wait_until: tiempo agotado marca fallo" "$E2E_STATUS" "1"
expect "wait_until: respeta el tiempo máximo (≥ 2000 ms)" "$([[ $WAITED_MS -ge 2000 ]] && echo yes || echo no)" "yes"

# --- synced (con catalog_stock e inventory_stock simulados, sin Docker) ---
synced_with() { # synced_with <catálogo> <inventario>
  eval "catalog_stock() { echo '$1'; }; inventory_stock() { echo '$2'; }"
  synced X && echo yes || echo no
}
expect "synced: dos enteros iguales" "$(synced_with 5 5)" "yes"
expect "synced: enteros distintos" "$(synced_with 5 4)" "no"
expect "synced: ambos vacíos" "$(synced_with '' '')" "no"
expect "synced: ambos null" "$(synced_with null null)" "no"
expect "synced: catálogo vacío e Inventario entero" "$(synced_with '' 5)" "no"

# --- Inyección de eventos (sin Docker) ---
identifier="$(new_uuid)"
expect "new_uuid: formato UUID" \
  "$([[ "$identifier" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] && echo yes || echo no)" "yes"
expect "new_uuid: dos valores distintos" "$([[ "$(new_uuid)" != "$(new_uuid)" ]] && echo yes || echo no)" "yes"

first_base="$(version_base)"
expect "version_base: milisegundos desde la época (13 dígitos)" \
  "$([[ "$first_base" =~ ^[0-9]{13}$ ]] && echo yes || echo no)" "yes"
sleep 0.01
expect "version_base: crece con el tiempo" "$([[ "$(version_base)" -gt "$first_base" ]] && echo yes || echo no)" "yes"

event="$(build_event "$identifier" "CALCETINES-PACK-3" 15 "$first_base" ONLINE)"
expect "build_event: JSON válido con los 7 campos del contrato" \
  "$(jq -r '[.eventId, .eventType, .sku, .newStock, .stockVersion, .channel, .occurredAt] | map(select(. != null)) | length' <<<"$event")" "7"
expect "build_event: eventType" "$(jq -r '.eventType' <<<"$event")" "InventoryStockChanged"
expect "build_event: eventId" "$(jq -r '.eventId' <<<"$event")" "$identifier"
expect "build_event: newStock es un número" "$(jq -r '.newStock | type' <<<"$event")" "number"
expect "build_event: stockVersion es un número con el valor dado" "$(jq -r '.stockVersion' <<<"$event")" "$first_base"
expect "build_event: canal indicado" "$(jq -r '.channel' <<<"$event")" "ONLINE"
expect "build_event: canal por defecto POS" "$(build_event "$identifier" X 1 1 | jq -r '.channel')" "POS"
expect "build_event: occurredAt en UTC con milisegundos" \
  "$([[ "$(jq -r '.occurredAt' <<<"$event")" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$ ]] && echo yes || echo no)" "yes"

awkward_body=$'{"a": "con \\"comillas\\"",\n "b": 1}'
publish_body="$(management_publish_body "$awkward_body")"
expect "management_publish_body: conserva el cuerpo tal cual (comillas y saltos de línea)" \
  "$(jq -r '.payload' <<<"$publish_body")" "$awkward_body"
expect "management_publish_body: clave de enrutamiento" "$(jq -r '.routing_key' <<<"$publish_body")" "inventory.stock.changed"
expect "management_publish_body: codificación del cuerpo" "$(jq -r '.payload_encoding' <<<"$publish_body")" "string"
expect "management_publish_body: mensaje persistente" "$(jq -r '.properties.delivery_mode' <<<"$publish_body")" "2"

if (( failures > 0 )); then
  echo "Autoprueba FALLÓ ($failures)"
  exit 1
fi
echo "Autoprueba OK"
