package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight scheduler for agent pilot ticks.
 *
 * The scheduler never spawns agents by itself. It only ticks characters that
 * have already been explicitly entered through AgentSpawnCoordinator.
 */
public final class AgentTickScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentTickScheduler.class);
    private static final long DEFAULT_TICK_INTERVAL_MILLIS = 5_000L;

    private final AgentSpawnCoordinator spawnCoordinator;
    private final AgentPilotService pilotService;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final Map<Integer, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private ScheduledFuture<?> task;

    public AgentTickScheduler(AgentSpawnCoordinator spawnCoordinator, AgentPilotService pilotService) {
        this.spawnCoordinator = spawnCoordinator;
        this.pilotService = pilotService;
    }

    public synchronized void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }

        task = TimerManager.getInstance().registerMaintenance(
                this::tickEnteredAgents,
                DEFAULT_TICK_INTERVAL_MILLIS,
                DEFAULT_TICK_INTERVAL_MILLIS
        );
        log.info("Agent tick scheduler started with {} ms interval", DEFAULT_TICK_INTERVAL_MILLIS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        log.info("Agent tick scheduler stopped");
    }

    public void tickEnteredAgents() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }

        try {
            List<AgentManagedCharacter> agents = spawnCoordinator.enteredCharactersSnapshot();
            for (AgentManagedCharacter managed : agents) {
                try {
                    AgentPilotTickResult result = pilotService.tick(managed);
                    consecutiveFailures.remove(managed.profileId());
                    log.debug("Agent profile {} tick planned {} with dispatch {}",
                            managed.profileId(), result.intent().type(), result.dispatchResult().status());
                } catch (Exception e) {
                    int failures = consecutiveFailures.merge(managed.profileId(), 1, Integer::sum);
                    log.warn("Agent profile {} tick failed ({}/3)", managed.profileId(), failures, e);
                    if (failures >= 3) {
                        consecutiveFailures.remove(managed.profileId());
                        spawnCoordinator.release(managed.profile(), "Released after 3 consecutive agent tick failures");
                    }
                }
            }
        } finally {
            ticking.set(false);
        }
    }
}
