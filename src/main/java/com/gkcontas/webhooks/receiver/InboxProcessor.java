package com.gkcontas.webhooks.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the inbox. Each event is locked, handled and marked processed in one
 * transaction: if the handler's own writes go to this database, they commit
 * together with the mark, and a crash in between rolls both back. The event is
 * then picked up again, never half done.
 */
@Slf4j
@Component
public class InboxProcessor {

    private final InboxRepository inbox;
    private final BusinessHandler handler;
    private final TransactionTemplate transaction;
    private final ReceiverProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public InboxProcessor(InboxRepository inbox, BusinessHandler handler, TransactionTemplate transaction,
                          ReceiverProperties properties) {
        this.inbox = inbox;
        this.handler = handler;
        this.transaction = transaction;
        this.properties = properties;
    }

    /** @return events processed in this run */
    public int processAvailable() {
        List<Future<Integer>> workers = new ArrayList<>(properties.workers());
        for (int i = 0; i < properties.workers(); i++) {
            workers.add(executor.submit(this::drain));
        }
        int processed = 0;
        for (Future<Integer> worker : workers) {
            try {
                processed += worker.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.error("inbox worker crashed", e.getCause());
            }
        }
        return processed;
    }

    private int drain() {
        int processed = 0;
        while (Boolean.TRUE.equals(transaction.execute(status -> processOne()))) {
            processed++;
        }
        return processed;
    }

    private boolean processOne() {
        Optional<String> next = inbox.lockNextReceived();
        if (next.isEmpty()) {
            return false;
        }
        handler.handle(next.get());
        inbox.markProcessed(next.get());
        return true;
    }
}
