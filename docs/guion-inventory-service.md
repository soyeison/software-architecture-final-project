# Guion: Servicio de Inventario

Guion para explicar el servicio de Inventario: qué es, cómo se construyó, las decisiones que más importan y
una demostración en vivo de una venta por POS. Acompaña al guion general de la demo
([`guion-demo.md`](guion-demo.md)) y a las pruebas de escritorio ([`pruebas-de-escritorio.md`](pruebas-de-escritorio.md)).

**Duración sugerida:** 7 a 8 minutos (explicación 4 min, demostración 3 min).

## Antes de empezar

- Stack levantado y en estado inicial: `e2e/reset.sh`. Así `TENIS-RUNNER-42` tiene 5 unidades.
- Tener a la vista dos terminales: una con los comandos y otra con los logs
  (`docker compose logs -f inventory-service`).
- Requisitos: Docker, `curl`, `jq`. Puerto del servicio: `8081`.

---

## 1. Qué es y qué no es (1 min)

Inventario es el **único responsable del stock**. Ninguna otra parte del sistema decide cuántas unidades quedan.

- **Hace:** descuenta stock en cada venta, rechaza las ventas sin existencias, guarda el evento de cambio y lo publica.
- **No hace:** no sirve la consulta pública de disponibilidad (eso es del Catálogo) y no conoce los canales de venta
  (POS y online se simulan con llamadas REST).

Sus endpoints principales:

| Método y ruta | Quién lo usa | Qué hace |
|---|---|---|
| `POST /api/v1/inventory/{sku}/sales` | POS y online (scope `inventory:sell`) | Registra una venta: `201` con el stock restante |
| `GET /api/v1/inventory/{sku}` | Operadores (scope `inventory:read`) | Stock actual, para uso interno |
| `GET /api/v1/inventory/outbox` | Diagnóstico de la demo (público) | Eventos del outbox, los 50 más recientes |
| `GET /actuator/health` | Compose | Salud del servicio |

Los errores siguen RFC 9457 (`application/problem+json`): `401` sin token válido, `403` sin el permiso, `409` sin stock.

## 2. Cómo se construyó (2 min)

Se construyó en fases, cada una con su especificación, su plan y pruebas escritas antes del código (TDD):

1. **Contrato primero.** Antes de programar, se escribieron la especificación OpenAPI del servicio y el esquema JSON del
   evento. El código debe cumplirlos, y `scripts/verify-contracts.sh` lo comprueba.
2. **Datos y esquema.** Tres migraciones de Flyway: la tabla `stock` (con la semilla de los 7 SKU), la tabla `outbox`
   y el índice de los pendientes. Flyway es el único dueño del esquema: Hibernate solo valida.
3. **Dominio.** La entidad `Stock` tiene la regla de negocio de descontar unidades. No tiene setters: el estado solo cambia
   con `sell(...)`, que rechaza cantidades no positivas y ventas por encima del stock.
4. **Venta y transacción.** El caso de uso `RegisterSaleService` descuenta el stock y escribe el evento en el outbox
   **en la misma transacción**.
5. **Publicador.** `OutboxRelay` envía los eventos pendientes a RabbitMQ con confirmación del broker. Lo dispara un
   planificador cada 500 ms.
6. **Seguridad.** JWT HS256 sin sesión, con dos permisos (`inventory:sell` e `inventory:read`) y una regla por defecto
   de **denegar todo lo que no esté declarado**.
7. **Calidad.** 151 pruebas (unitarias, de arquitectura e integración con PostgreSQL y RabbitMQ reales), contenedor sin
   usuario `root` y prueba de humo propia (`scripts/smoke-inventory-service.sh`).

## 3. Consideraciones clave (3 min)

Explica cada una en una frase y muestra dónde está en el código si preguntan.

**a. El evento se guarda con la venta (patrón *Transactional Outbox*).**
Si el stock se descontara y el evento se publicara por separado, un fallo entre los dos dejaría el sistema incoherente.
Aquí el evento se escribe en la tabla `outbox` dentro de la misma transacción: o se guardan la venta y el evento, o no se
guarda ninguno. `OutboxRecorder` exige una transacción abierta (`MANDATORY`), así que el diseño lo impone.

**b. Publicar no bloquea la venta.**
Si RabbitMQ cae, la venta igual se confirma con `201` y el evento queda `PENDING`. Cuando el broker vuelve, el publicador
los envía. Esto es el escenario 8: **cero eventos perdidos**.

**c. Confirmación del broker y orden.**
El publicador espera la confirmación de RabbitMQ antes de marcar un evento `PUBLISHED`. Si un envío falla, **detiene el
lote** y guarda el error: así no se publica un evento posterior antes que uno anterior del mismo producto.

**d. Bloqueo optimista para la carrera.**
Dos ventas simultáneas de la última unidad no pueden vender las dos. La entidad tiene un `@Version`; si la segunda
transacción guarda sobre una versión vieja, falla, se reintenta (hasta 5 veces, `inventory.sale.max-attempts`) y encuentra
stock 0: obtiene `409`. Esto es el escenario 3.

**e. El evento lleva el stock absoluto, no la diferencia.**
El mensaje dice "el stock ahora es 4", no "se descontó 1". Así un mensaje repetido o llegado tarde no corrompe el
estado: el consumidor compara la versión y descarta lo viejo.

**f. Entrada estricta.**
Jackson rechaza campos desconocidos, decimales en campos enteros y números como texto. Un error de entrada da `400` en
vez de entrar al sistema con un valor distorsionado.

**g. La hora es en milisegundos y UTC.**
Todo instante se trunca a milisegundos, así la latencia `updatedAt − occurredAt` que mide el Catálogo es comparable entre
servicios.

**h. El secreto no se filtra.**
`JWT_SECRET` no tiene valor por defecto: si falta o tiene menos de 32 bytes, el servicio no arranca. Su valor nunca
aparece en logs ni en mensajes de error.

## 4. Demostración: venta POS (3 min)

Ejecuta los pasos en orden. Cada uno tiene un propósito; si el público se pierde, repite el comando del paso 3.

**Paso 1 — Estado inicial.** Mostrar el stock con permiso de operador:

```bash
curl -s -H "Authorization: Bearer $(scripts/generate-token.sh operator)" \
  http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42 | jq
```

Esperado: `stock: 5`.

**Paso 2 — Sin token, la venta se rechaza.** Muestra la seguridad:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42/sales \
  -H 'Content-Type: application/json' -d '{"quantity":1,"channel":"POS"}'
```

Esperado: `401`.

**Paso 3 — La venta POS.** Con el token del POS:

```bash
TOKEN="$(scripts/generate-token.sh pos)"
curl -s -X POST http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42/sales \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"quantity":1,"channel":"POS"}' | jq
```

Esperado: `201`, con `remainingStock: 4`, `channel: "POS"` y un `saleId`. Ese `saleId` es el `eventId` del evento.
(Verificado con el stack de desarrollo: `401` y después `201` con `remainingStock: 4`.)

**Paso 4 — El log del servicio.** En la otra terminal, mostrar las líneas que dejó la venta:

```bash
docker compose logs inventory-service | grep -E "outbox_recorded|sale_registered|outbox_published" | tail -3
```

Señalar, en este orden: `outbox_recorded` (el evento se guardó en la misma transacción que la venta),
`sale_registered` (el `saleId` y el stock restante) y `outbox_published` (el broker confirmó el envío). El `sku` aparece
en las tres líneas; el `eventId` aparece en la del publicador, que es la única que lo lleva en el contexto del log.
Mostrar el comando completo (sin `grep`) solo si el público quiere ver el arranque del servicio: son muchas líneas.

**Paso 5 — El outbox.** Mostrar que el evento quedó publicado:

```bash
curl -s "http://localhost:8081/api/v1/inventory/outbox?status=PUBLISHED" \
  | jq '.[] | select(.sku=="TENIS-RUNNER-42")' | head -20
```

Esperado: el evento con `status: "PUBLISHED"` y el mismo `eventId` del `saleId`.

**Paso 6 — El Catálogo lo refleja.** La prueba de extremo a extremo completa, con narración en español:

```bash
e2e/01-pos-sale.sh --demo
```

Pulsa Enter entre pasos. Esperado: el Catálogo muestra stock 4 con su `lastEventLatencyMs` (en la última medición,
781 ms en esta venta; el objetivo es menos de 1 s, y el promedio medido es de unos 330 ms).

Opcional: en la consola de RabbitMQ (<http://localhost:15672>), la cola `catalog.inventory-stock-changed` recibió el
mensaje y quedó vacía porque el Catálogo lo confirmó.

## 5. Preguntas probables

| Pregunta | Respuesta corta |
|---|---|
| ¿Qué pasa si RabbitMQ se cae justo después de la venta? | La venta ya quedó confirmada con `201` y el evento en el outbox como `PENDING`. Al volver el broker se publica (escenario 8). |
| ¿Puede un evento llegar dos veces? | Sí: el publicador puede reenviar si no recibe la confirmación. Por eso el consumidor deduplica por `eventId`. |
| ¿Por qué no publicar directo después de guardar? | Un fallo entre guardar y publicar perdería el evento. El outbox lo evita sin una transacción distribuida. |
| ¿Y si dos ventas compiten por la última unidad? | Gana la primera en guardar; la otra reintenta, ve stock 0 y recibe `409` (escenario 3). |
| ¿Por qué el Catálogo no consulta a Inventario? | Para que los servicios sean independientes: el Catálogo solo recibe eventos y nunca lee su base de datos. |
| ¿Cuánto tarda en reflejarse una venta? | En las mediciones, entre unos 25 y 445 ms (p95 de unos 440 ms), frente a 30 minutos del proceso por lotes. |

## Notas para quien presente

- Si el stock de `TENIS-RUNNER-42` ya bajó (por ejemplo, tras varios ensayos), reinicia con `e2e/reset.sh` antes del
  paso 1; si no, el paso 3 mostrará otro `remainingStock`.
- El paso 2 y el 3 usan `curl` directo; si prefieres, `e2e/10-security.sh` demuestra los permisos en un solo comando.
- Los números de latencia cambian entre ejecuciones; di siempre "en las mediciones" y muestra
  `docs/results/latency.md` si te lo piden.
