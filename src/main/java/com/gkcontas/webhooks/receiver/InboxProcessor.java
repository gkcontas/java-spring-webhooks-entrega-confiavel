package com.gkcontas.webhooks.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the inbox. Each event is locked, handled and marked processed in one
 * transaction: if the handler's own writes go to this database, they commit
 * together with the mark, and a crash in between rolls both back. The event is
 * then picked up again, never half done.
 *
 * <p>Two entry points. {@link #topUp()} is what the scheduler calls: it starts
 * workers into the free slots and returns at once, so a worker that found the
 * inbox momentarily empty is replaced on the next tick. A run that starts N
 * drainers and waits for all of them would not do: under a steady trickle the
 * others exit on an empty read, the last one never runs out of work, and the run
 * never ends - N workers configured, one working.
 */
@Slf4j
@Component
public class InboxProcessor {

    private final InboxRepository inbox;
    private final BusinessHandler handler;
    private final TransactionTemplate transaction;
    private final ReceiverProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;

    public InboxProcessor(InboxRepository inbox, BusinessHandler handler, TransactionTemplate transaction,
                          ReceiverProperties properties) {
        this.inbox = inbox;
        this.handler = handler;
        this.transaction = transaction;
        this.properties = properties;
        this.slots = new Semaphore(properties.workers());
    }

    /** Starts a worker in every free slot, up to the number of waiting events. Never blocks. */
    public int topUp() {
        int waiting = inbox.countReceived(properties.workers());
        int started = 0;
        while (started < waiting && slots.tryAcquire()) {
            executor.submit(() -> {
                try {
                    drain();
                } catch (RuntimeException e) {
                    log.error("inbox worker crashed", e);
                } finally {
                    slots.release();
                }
            });
            started++;
        }
        return started;
    }

    public int busyWorkers() {
        return properties.workers() - slots.availablePermits();
    }

    /** Synchronous variant for tests and tooling: drains with every worker and waits. */
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
