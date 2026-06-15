package server.runtime;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class ServerExecutors {
    private static final Logger log = LoggerFactory.getLogger(ServerExecutors.class);
    private static final ServerExecutors instance = new ServerExecutors();

    private ThreadPoolExecutor background;
    private ThreadPoolExecutor persistence;
    private ScheduledThreadPoolExecutor gameplayScheduler;
    private ScheduledThreadPoolExecutor maintenanceScheduler;

    private ServerExecutors() {
    }

    public static ServerExecutors getInstance() {
        return instance;
    }

    public synchronized void start() {
        if (background != null && !background.isShutdown()) {
            return;
        }

        background = createPool("Background", YamlConfig.config.server.BACKGROUND_THREADS,
                YamlConfig.config.server.BACKGROUND_QUEUE_CAPACITY);
        persistence = createPool("Persistence", YamlConfig.config.server.PERSISTENCE_THREADS,
                YamlConfig.config.server.PERSISTENCE_QUEUE_CAPACITY);
        gameplayScheduler = createScheduler("GameplayTimer", YamlConfig.config.server.GAMEPLAY_SCHEDULER_THREADS);
        maintenanceScheduler = createScheduler("MaintenanceTimer", YamlConfig.config.server.MAINTENANCE_SCHEDULER_THREADS);

        RuntimeMetrics.getInstance().bindQueueDepths(
                () -> background.getQueue().size(),
                () -> persistence.getQueue().size(),
                () -> gameplayScheduler.getQueue().size(),
                () -> maintenanceScheduler.getQueue().size());
        RuntimeMetrics.getInstance().bindActiveCounts(
                background::getActiveCount,
                persistence::getActiveCount,
                gameplayScheduler::getActiveCount,
                maintenanceScheduler::getActiveCount);
    }

    private ThreadPoolExecutor createPool(String name, int threads, int queueCapacity) {
        int safeThreads = Math.max(1, threads);
        int safeCapacity = Math.max(safeThreads, queueCapacity);
        return new ThreadPoolExecutor(safeThreads, safeThreads, 30, SECONDS,
                new ArrayBlockingQueue<>(safeCapacity), namedFactory("Cosmic-" + name),
                (task, executor) -> {
                    RuntimeMetrics.getInstance().recordRejectedTask();
                    if (executor.isShutdown()) {
                        throw new RejectedExecutionException(name + " executor is shut down");
                    }
                    log.warn("{} queue is full; applying caller backpressure", name);
                    task.run();
                });
    }

    private ScheduledThreadPoolExecutor createScheduler(String name, int threads) {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(Math.max(1, threads), namedFactory("Cosmic-" + name));
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.getAndIncrement());
            thread.setUncaughtExceptionHandler((t, error) ->
                    log.error("Uncaught exception on {}", t.getName(), error));
            return thread;
        };
    }

    public void executeBackground(Runnable task) {
        start();
        background.execute(task);
    }

    public void executePersistence(Runnable task) {
        start();
        RuntimeMetrics.getInstance().recordPersistenceQueued();
        try {
            persistence.execute(() -> {
                try {
                    task.run();
                } finally {
                    RuntimeMetrics.getInstance().recordPersistenceCompleted();
                }
            });
        } catch (RejectedExecutionException e) {
            RuntimeMetrics.getInstance().recordPersistenceRejected();
            throw e;
        }
    }

    public ScheduledThreadPoolExecutor gameplayScheduler() {
        start();
        return gameplayScheduler;
    }

    public ScheduledThreadPoolExecutor maintenanceScheduler() {
        start();
        return maintenanceScheduler;
    }

    public synchronized void stop() {
        shutdown(gameplayScheduler, "gameplay scheduler");
        shutdown(maintenanceScheduler, "maintenance scheduler");
        shutdown(background, "background");
        shutdown(persistence, "persistence");
    }

    private void shutdown(java.util.concurrent.ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, SECONDS)) {
                log.warn("{} executor did not stop within 30 seconds; cancelling remaining work", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
