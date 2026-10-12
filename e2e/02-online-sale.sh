#!/usr/bin/env bash
# Escenario 2 — Venta online (spec global §4.2). Uso: e2e/02-online-sale.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/sale-flow.sh"
run_sale_scenario "02 Venta online" ONLINE "$@"
