#!/usr/bin/env bash
# Genera un JWT HS256 de DEMO para el servicio de Inventario (spec de Inventario §5).
# Uso: scripts/generate-token.sh <pos|online|operator|no-scope>
# El secreto sale de JWT_SECRET; si no está definido, de .env y luego de .env.example, de modo que
# coincide con el Compose sin configurar nada. Imprime solo el token.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ISSUER="retail-demo-auth"
VALIDITY_SECONDS=86400

case "${1:-}" in
  pos)      subject="pos-store-001"; scope="inventory:sell" ;;
  online)   subject="online-store";  scope="inventory:sell" ;;
  operator) subject="operator";      scope="inventory:read" ;;
  no-scope) subject="test-client";   scope="" ;;
  *)
    echo "Uso: $0 <pos|online|operator|no-scope>" >&2
    exit 2
    ;;
esac

secret="${JWT_SECRET:-}"
if [[ -z "$secret" ]]; then
  for environment_file in "$ROOT/.env" "$ROOT/.env.example"; do
    if [[ -f "$environment_file" ]]; then
      secret="$(grep -E '^JWT_SECRET=' "$environment_file" | tail -n 1 | cut -d '=' -f 2-)"
      [[ -n "$secret" ]] && break
    fi
  done
fi
if [[ -z "$secret" ]]; then
  echo "No se encontró JWT_SECRET (ni en el entorno, ni en .env, ni en .env.example)" >&2
  exit 1
fi

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

issued_at="$(date +%s)"
expires_at=$((issued_at + VALIDITY_SECONDS))
header='{"alg":"HS256","typ":"JWT"}'
payload="$(printf '{"iss":"%s","sub":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$ISSUER" "$subject" "$scope" "$issued_at" "$expires_at")"

unsigned_token="$(printf '%s' "$header" | base64url).$(printf '%s' "$payload" | base64url)"
signature="$(printf '%s' "$unsigned_token" | openssl dgst -sha256 -hmac "$secret" -binary | base64url)"
printf '%s.%s\n' "$unsigned_token" "$signature"
