package server.runtime;

import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RuntimeModuleManager {
    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);
    private static final RuntimeModuleManager instance = new RuntimeModuleManager();

    private final RuntimeEventBus eventBus = new RuntimeEventBus();
    private final List<RuntimeModule> modules = new CopyOnWriteArrayList<>();
    private volatile boolean started;

    private RuntimeModuleManager() {
    }

    public static RuntimeModuleManager getInstance() {
        return instance;
    }

    public RuntimeEventBus eventBus() {
        return eventBus;
    }

    public void register(RuntimeModule module) {
        if (module == null) {
            return;
        }
        if (started) {
            throw new IllegalStateException("Runtime modules must be registered before startup: " + module.name());
        }
        for (RuntimeModule registeredModule : modules) {
            if (registeredModule.name().equals(module.name())) {
                return;
            }
        }
        modules.add(module);
    }

    public synchronized void start(Server server) {
        if (started) {
            return;
        }

        RuntimeModuleContext context = new RuntimeModuleContext(server, eventBus);
        eventBus.publish(ServerLifecycleEvent.of(ServerLifecycleEvent.Type.STARTING));
        for (RuntimeModule module : modules) {
            try {
                module.start(context);
                log.info("Started runtime module {}", module.name());
            } catch (Exception e) {
                log.error("Runtime module {} failed to start", module.name(), e);
            }
        }
        started = true;
        log.info("Runtime module manager started with {} modules", modules.size());
    }

    public synchronized void markOnline() {
        if (started) {
            eventBus.publish(ServerLifecycleEvent.of(ServerLifecycleEvent.Type.ONLINE));
        }
    }

    public synchronized void stop(Server server) {
        if (!started) {
            return;
        }

        RuntimeModuleContext context = new RuntimeModuleContext(server, eventBus);
        eventBus.publish(ServerLifecycleEvent.of(ServerLifecycleEvent.Type.STOPPING));
        for (int i = modules.size() - 1; i >= 0; i--) {
            RuntimeModule module = modules.get(i);
            try {
                module.stop(context);
                log.info("Stopped runtime module {}", module.name());
            } catch (Exception e) {
                log.warn("Runtime module {} failed to stop cleanly", module.name(), e);
            }
        }
        eventBus.publish(ServerLifecycleEvent.of(ServerLifecycleEvent.Type.STOPPED));
        eventBus.clear();
        started = false;
    }
}
