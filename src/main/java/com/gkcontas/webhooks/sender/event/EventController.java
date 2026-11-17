package com.gkcontas.webhooks.sender.event;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    public record PublishEvent(
            @NotBlank(message = "type is required")
            @Pattern(regexp = "[a-z][a-z0-9_.]{0,99}", message = "type must be lowercase dotted, like order.paid")
            String type,
            @NotNull(message = "data is required") JsonNode data) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventService.Published publish(@Valid @RequestBody PublishEvent request) {
        return service.publish(request.type(), request.data());
    }
}
