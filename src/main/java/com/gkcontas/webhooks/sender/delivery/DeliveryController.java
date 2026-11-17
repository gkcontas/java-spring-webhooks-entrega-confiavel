package com.gkcontas.webhooks.sender.delivery;

import com.gkcontas.webhooks.error.ConflictException;
import com.gkcontas.webhooks.error.NotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The failure queue is just the FAILED rows; redelivery puts them back in line. */
@Validated
@RestController
@RequestMapping("/deliveries")
public class DeliveryController {

    private final DeliveryRepository repository;

    public DeliveryController(DeliveryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<DeliveryRepository.DeliveryView> list(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "100")
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 1000, message = "limit must be at most 1000") int limit) {
        return repository.list(status, eventId, limit);
    }

    @GetMapping("/{id}/attempts")
    public List<DeliveryRepository.AttemptView> attempts(@PathVariable long id) {
        repository.find(id).orElseThrow(() -> new NotFoundException("delivery not found"));
        return repository.attempts(id);
    }

    /**
     * Same webhook-id as before: if the receiver did process it the first time,
     * its idempotency turns the resend into a no-op.
     */
    @PostMapping("/{id}/redeliver")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> redeliver(@PathVariable long id) {
        DeliveryRepository.DeliveryView delivery = repository.find(id)
                .orElseThrow(() -> new NotFoundException("delivery not found"));
        if (repository.redeliver(id) == 0) {
            throw new ConflictException("delivery is " + delivery.status() + "; only FAILED or DELIVERED can be resent");
        }
        return Map.of("id", id, "status", DeliveryStatus.PENDING);
    }

    @PostMapping("/redeliver-failed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> redeliverFailed(@RequestParam(required = false) UUID subscriptionId) {
        return Map.of("requeued", repository.redeliverFailed(subscriptionId));
    }
}
