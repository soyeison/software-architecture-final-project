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

if (( failures > 0 )); then
  echo "Autoprueba FALLÓ ($failures)"
  exit 1
fi
echo "Autoprueba OK"
