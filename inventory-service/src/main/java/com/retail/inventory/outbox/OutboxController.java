package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnóstico para la demo (escenario "RabbitMQ caído"). Es público por diseño: en un entorno real
 * estaría protegido o no existiría.
 */
@RestController
@RequestMapping("/api/v1/inventory/outbox")
public class OutboxController {

    private final ListOutboxEventsService listOutboxEventsService;

    public OutboxController(ListOutboxEventsService listOutboxEventsService) {
        this.listOutboxEventsService = listOutboxEventsService;
    }

    @GetMapping
    public List<OutboxEventResponse> listOutboxEvents(@RequestParam(required = false) OutboxStatus status) {
        return listOutboxEventsService.list(status).stream().map(OutboxEventResponse::from).toList();
    }
}
