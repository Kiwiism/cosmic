package server.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

public final class RuntimeMetrics implements RuntimeMetricsMBean {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMetrics.class);
    private static final RuntimeMetrics instance = new RuntimeMetrics();

    private final AtomicLong packetsHandled = new AtomicLong();
    private final AtomicLong slowPackets = new AtomicLong();
    private final AtomicLong scheduledTasksCompleted = new AtomicLong();
    private final AtomicLong slowScheduledTasks = new AtomicLong();
    private final AtomicLong rejectedTasks = new AtomicLong();
    private final AtomicLong persistenceQueued = new AtomicLong();
    private final AtomicLong persistenceCompleted = new AtomicLong();
    private final AtomicLong persistenceRejected = new AtomicLong();
    private final AtomicLong databaseConnectionsAcquired = new AtomicLong();
    private final AtomicLong databaseConnectionWaitMillis = new AtomicLong();
    private final AtomicLong characterSavesCompleted = new AtomicLong();
    private final AtomicLong characterSaveFailures = new AtomicLong();
    private final AtomicLong characterSaveMillis = new AtomicLong();

    private volatile IntSupplier backgroundQueueDepth = () -> 0;
    private volatile IntSupplier persistenceQueueDepth = () -> 0;
    private volatile IntSupplier gameplaySchedulerQueueDepth = () -> 0;
    private volatile IntSupplier maintenanceSchedulerQueueDepth = () -> 0;
    private volatile IntSupplier backgroundActiveCount = () -> 0;
    private volatile IntSupplier persistenceActiveCount = () -> 0;
    private volatile IntSupplier gameplaySchedulerActiveCount = () -> 0;
    private volatile IntSupplier maintenanceSchedulerActiveCount = () -> 0;

    private RuntimeMetrics() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("cosmic:type=RuntimeMetrics");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
            }
        } catch (Exception e) {
            log.warn("Unable to register runtime metrics MBean", e);
        }
    }

    public static RuntimeMetrics getInstance() {
        return instance;
    }

    public void recordPacket(long elapsedMillis, long slowThresholdMillis) {
        packetsHandled.incrementAndGet();
        if (elapsedMillis >= slowThresholdMillis) {
            slowPackets.incrementAndGet();
        }
    }

    public void recordScheduledTask(long elapsedMillis, long slowThresholdMillis) {
        scheduledTasksCompleted.incrementAndGet();
        if (elapsedMillis >= slowThresholdMillis) {
            slowScheduledTasks.incrementAndGet();
        }
    }

    public void recordRejectedTask() {
        rejectedTasks.incrementAndGet();
    }

    public void recordPersistenceQueued() {
        persistenceQueued.incrementAndGet();
    }

    public void recordPersistenceCompleted() {
        persistenceCompleted.incrementAndGet();
    }

    public void recordPersistenceRejected() {
        persistenceRejected.incrementAndGet();
    }

    public void recordDatabaseConnection(long elapsedMillis) {
        databaseConnectionsAcquired.incrementAndGet();
        databaseConnectionWaitMillis.addAndGet(elapsedMillis);
    }

    public void recordCharacterSave(long elapsedMillis, boolean success) {
        characterSaveMillis.addAndGet(elapsedMillis);
        if (success) {
            characterSavesCompleted.incrementAndGet();
        } else {
            characterSaveFailures.incrementAndGet();
        }
    }

    public void bindQueueDepths(IntSupplier background, IntSupplier persistence,
                                IntSupplier gameplayScheduler, IntSupplier maintenanceScheduler) {
        backgroundQueueDepth = background;
        persistenceQueueDepth = persistence;
        gameplaySchedulerQueueDepth = gameplayScheduler;
        maintenanceSchedulerQueueDepth = maintenanceScheduler;
    }

    public void bindActiveCounts(IntSupplier background, IntSupplier persistence,
                                 IntSupplier gameplayScheduler, IntSupplier maintenanceScheduler) {
        backgroundActiveCount = background;
        persistenceActiveCount = persistence;
        gameplaySchedulerActiveCount = gameplayScheduler;
        maintenanceSchedulerActiveCount = maintenanceScheduler;
    }

    public String healthSnapshot() {
        return "packets=" + packetsHandled.get()
                + ", slowPackets=" + slowPackets.get()
                + ", schedulerQueue=" + getGameplaySchedulerQueueDepth()
                + ", maintenanceQueue=" + getMaintenanceSchedulerQueueDepth()
                + ", backgroundQueue=" + getBackgroundQueueDepth()
                + ", persistenceQueue=" + getPersistenceQueueDepth()
                + ", activeWorkers={background=" + getBackgroundActiveCount()
                + ", persistence=" + getPersistenceActiveCount()
                + ", gameplay=" + getGameplaySchedulerActiveCount()
                + ", maintenance=" + getMaintenanceSchedulerActiveCount() + "}"
                + ", saveFailures=" + characterSaveFailures.get()
                + ", rejectedTasks=" + rejectedTasks.get();
    }

    @Override
    public long getPacketsHandled() {
        return packetsHandled.get();
    }

    @Override
    public long getSlowPackets() {
        return slowPackets.get();
    }

    @Override
    public long getScheduledTasksCompleted() {
        return scheduledTasksCompleted.get();
    }

    @Override
    public long getSlowScheduledTasks() {
        return slowScheduledTasks.get();
    }

    @Override
    public long getRejectedTasks() {
        return rejectedTasks.get();
    }

    @Override
    public long getPersistenceQueued() {
        return persistenceQueued.get();
    }

    @Override
    public long getPersistenceCompleted() {
        return persistenceCompleted.get();
    }

    @Override
    public long getPersistenceRejected() {
        return persistenceRejected.get();
    }

    @Override
    public long getDatabaseConnectionsAcquired() {
        return databaseConnectionsAcquired.get();
    }

    @Override
    public long getDatabaseConnectionWaitMillis() {
        return databaseConnectionWaitMillis.get();
    }

    @Override
    public long getCharacterSavesCompleted() {
        return characterSavesCompleted.get();
    }

    @Override
    public long getCharacterSaveFailures() {
        return characterSaveFailures.get();
    }

    @Override
    public long getCharacterSaveMillis() {
        return characterSaveMillis.get();
    }

    @Override
    public int getBackgroundQueueDepth() {
        return backgroundQueueDepth.getAsInt();
    }

    @Override
    public int getPersistenceQueueDepth() {
        return persistenceQueueDepth.getAsInt();
    }

    @Override
    public int getGameplaySchedulerQueueDepth() {
        return gameplaySchedulerQueueDepth.getAsInt();
    }

    @Override
    public int getMaintenanceSchedulerQueueDepth() {
        return maintenanceSchedulerQueueDepth.getAsInt();
    }

    @Override
    public int getBackgroundActiveCount() {
        return backgroundActiveCount.getAsInt();
    }

    @Override
    public int getPersistenceActiveCount() {
        return persistenceActiveCount.getAsInt();
    }

    @Override
    public int getGameplaySchedulerActiveCount() {
        return gameplaySchedulerActiveCount.getAsInt();
    }

    @Override
    public int getMaintenanceSchedulerActiveCount() {
        return maintenanceSchedulerActiveCount.getAsInt();
    }
}
