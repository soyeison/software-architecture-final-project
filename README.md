# Sincronización de inventario orientada a eventos (SOA)

Proyecto final del curso Arquitectura de Software — Politécnico Grancolombiano, Subgrupo 22.

Implementación del patrón **Event-Driven Messaging** para eliminar el desfase entre el stock real
(Servicio de Inventario) y la disponibilidad mostrada en los canales digitales (Servicio de
Catálogo), comunicados mediante RabbitMQ.

## Estructura
```
docs/contracts/           Contratos: OpenAPI, JSON Schema del evento (ver su README)
docs/superpowers/specs/   Especificaciones de diseño
docs/superpowers/plans/   Planes de implementación
infra/rabbitmq/           Topología y configuración de RabbitMQ
scripts/                  Scripts de verificación y utilidades
```

Para verificar los contratos: `scripts/verify-contracts.sh` (requiere Docker).
