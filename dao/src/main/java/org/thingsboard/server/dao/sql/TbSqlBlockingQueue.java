package org.thingsboard.server.dao.sql;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class TbSqlBlockingQueue<E> implements TbSqlQueue<E> {

    private final BlockingQueue<TbSqlQueueElement<E>> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger addedCount = new AtomicInteger();
    private final AtomicInteger savedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final TbSqlBlockingQueueParams params;

    private ExecutorService executor;
    private ScheduledLogExecutorComponent logExecutor;

    public TbSqlBlockingQueue(TbSqlBlockingQueueParams params) {
        this.params = params;
    }

    @Override
    public void init(ScheduledLogExecutorComponent logExecutor, Consumer<List<E>> saveFunction) {
        this.logExecutor = logExecutor;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            String logName = params.getLogName();
            int batchSize = params.getBatchSize();
            long maxDelay = params.getMaxDelay();
            List<TbSqlQueueElement<E>> entities = new ArrayList<>(batchSize);
            while (!Thread.interrupted()) {
                try {
                    long currentTs = System.currentTimeMillis();
                    TbSqlQueueElement<E> attr = queue.poll(maxDelay, TimeUnit.MILLISECONDS);
                    if (attr == null) {
                        continue;
                    } else {
                        entities.add(attr);
                    }
                    queue.drainTo(entities, batchSize - 1);
                    boolean fullPack = entities.size() == batchSize;
                    log.debug("[{}] Going to save {} entities", logName, entities.size());
                    saveFunction.accept(entities.stream().map(TbSqlQueueElement::getEntity).collect(Collectors.toList()));
                    entities.forEach(v -> v.getFuture().set(null));
                    savedCount.addAndGet(entities.size());
                    if (!fullPack) {
                        long remainingDelay = maxDelay - (System.currentTimeMillis() - currentTs);
                        if (remainingDelay > 0) {
                            Thread.sleep(remainingDelay);
                        }
                    }
                } catch (Exception e) {
                    failedCount.addAndGet(entities.size());
                    entities.forEach(entityFutureWrapper -> entityFutureWrapper.getFuture().setException(e));
                    if (e instanceof InterruptedException) {
                        log.info("[{}] Queue polling was interrupted", logName);
                        break;
                    } else {
                        log.error("[{}] Failed to save {} entities", logName, entities.size(), e);
                    }
                } finally {
                    entities.clear();
                }
            }
        });

        logExecutor.scheduleAtFixedRate(() -> {
            log.info("Attributes queueSize [{}] totalAdded [{}] totalSaved [{}] totalFailed [{}]",
                    queue.size(), addedCount.getAndSet(0), savedCount.getAndSet(0), failedCount.getAndSet(0));
        }, params.getStatsPrintIntervalMs(), params.getStatsPrintIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public ListenableFuture<Void> add(E element) {
        SettableFuture<Void> future = SettableFuture.create();
        queue.add(new TbSqlQueueElement<>(future, element));
        addedCount.incrementAndGet();
        return future;
    }
}
