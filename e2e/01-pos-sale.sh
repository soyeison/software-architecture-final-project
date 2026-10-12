#!/usr/bin/env bash
# Escenario 1 — Venta POS (spec global §4.2). Uso: e2e/01-pos-sale.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/sale-flow.sh"
run_sale_scenario "01 Venta POS" POS "$@"
