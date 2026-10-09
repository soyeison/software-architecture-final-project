# Proyecto final — Arquitectura de Software (Politécnico Grancolombiano, Subgrupo 22)

## Contexto
Retail multicanal con inconsistencia de inventario: las ventas descuentan stock en tiempo real,
pero el catálogo digital se actualiza por lotes cada 30 minutos. La solución aplica SOA con el
patrón **Event-Driven Messaging** (Erl, 2009), complementado con **Asynchronous Queuing** y
**Reliable Messaging**.

## Alcance de la implementación
Solo se construye el núcleo del flujo:
- **Servicio de Inventario** (productor): único responsable del stock; expone REST y publica el
  evento "Cambio de Inventario" tras actualizar el stock.
- **Servicio de Catálogo** (consumidor): mantiene una vista de disponibilidad de solo lectura,
  alimentada exclusivamente por eventos; expone REST de consulta.
- **RabbitMQ**: broker de mensajería entre ambos.

Ventas, API Gateway y canales de venta se simulan (llamadas REST / scripts).

## Stack
Java 25 + Spring Boot (Maven wrapper `./mvnw`), REST + JSON, RabbitMQ, PostgreSQL (una base por
servicio), JWT, Docker Compose.

## Reglas de arquitectura
- Catálogo nunca lee la base de datos de Inventario; solo recibe eventos.
- Los servicios se coordinan por coreografía (eventos), no hay orquestador central.
- Consumidores idempotentes (deduplicación por `eventId`), colas durables, ack manual y DLQ.

## Convenciones
- **Código en inglés**: identificadores, clases, métodos, variables, paquetes, endpoints, nombres
  de eventos/colas/exchanges, tablas y columnas.
- **Documentación y comentarios en español**: specs, planes, README, comentarios y Javadoc.
- **Mensajes de commit en inglés**, con formato Conventional Commits (`feat:`, `fix:`, `docs:`,
  `test:`, `chore:`, `refactor:`). Breves y puntuales: una línea de resumen y, solo si aporta,
  un cuerpo corto. Se permite incluir la línea `Co-Authored-By` de Claude.

## Flujo de trabajo (SDD + superpowers)
Fases: 0 repo · 1 diseño global · 2 contratos · 3 infraestructura · 4 Inventario ·
5 Catálogo · 6 integración/E2E · 7 pruebas de escritorio y demo · 8 documento y video.
- Specs en `docs/superpowers/specs/`, planes en `docs/superpowers/plans/`.
- Diseño antes de código; TDD en la implementación.
