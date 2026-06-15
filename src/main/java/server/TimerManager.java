/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server;

import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import config.YamlConfig;
import server.runtime.RuntimeMetrics;
import server.runtime.ServerExecutors;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TimerManager implements TimerManagerMBean {
    private static final Logger log = LoggerFactory.getLogger(TimerManager.class);
    private static final TimerManager instance = new TimerManager();

    public static TimerManager getInstance() {
        return instance;
    }

    private ScheduledThreadPoolExecutor ses;

    private TimerManager() {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        try {
            mBeanServer.registerMBean(this, new ObjectName("server:type=TimerManger"));
        } catch (Exception e) {
            log.warn("Unable to register TimerManager MBean", e);
        }
    }

    public void start() {
        if (ses != null && !ses.isShutdown() && !ses.isTerminated()) {
            return;
        }
        // The server clock must be based on wall time before login/session tasks can use it.
        Server.getInstance().forceUpdateCurrentTime();
        ServerExecutors.getInstance().start();
        ses = ServerExecutors.getInstance().gameplayScheduler();
    }

    public void stop() {
        // ServerExecutors owns the shared scheduler lifecycle.
    }

    public Runnable purge() {//Yay?
        return () -> {
            Server.getInstance().forceUpdateCurrentTime();
            gameplayScheduler().purge();
        };
    }

    public ScheduledFuture<?> register(Runnable r, long repeatTime, long delay) {
        return gameplayScheduler().scheduleAtFixedRate(new TimedRunnable(r), delay, repeatTime, MILLISECONDS);
    }

    public ScheduledFuture<?> register(Runnable r, long repeatTime) {
        return gameplayScheduler().scheduleAtFixedRate(new TimedRunnable(r), 0, repeatTime, MILLISECONDS);
    }

    public ScheduledFuture<?> schedule(Runnable r, long delay) {
        return gameplayScheduler().schedule(new TimedRunnable(r), Math.max(0, delay), MILLISECONDS);
    }

    public ScheduledFuture<?> registerMaintenance(Runnable r, long repeatTime, long delay) {
        return ServerExecutors.getInstance().maintenanceScheduler()
                .scheduleAtFixedRate(new TimedRunnable(r), delay, repeatTime, MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleMaintenance(Runnable r, long delay) {
        return ServerExecutors.getInstance().maintenanceScheduler()
                .schedule(new TimedRunnable(r), Math.max(0, delay), MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleAtTimestamp(Runnable r, long timestamp) {
        return schedule(r, timestamp - System.currentTimeMillis());
    }

    @Override
    public long getActiveCount() {
        return gameplayScheduler().getActiveCount();
    }

    @Override
    public long getCompletedTaskCount() {
        return gameplayScheduler().getCompletedTaskCount();
    }

    @Override
    public int getQueuedTasks() {
        return gameplayScheduler().getQueue().size();
    }

    @Override
    public long getTaskCount() {
        return gameplayScheduler().getTaskCount();
    }

    @Override
    public boolean isShutdown() {
        return ses == null || ses.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return ses == null || ses.isTerminated();
    }

    private ScheduledThreadPoolExecutor gameplayScheduler() {
        start();
        return ses;
    }


    private static class TimedRunnable implements Runnable {
        private final Runnable r;

        public TimedRunnable(Runnable r) {
            this.r = r;
        }

        @Override
        public void run() {
            long start = System.nanoTime();
            try {
                r.run();
            } catch (Throwable t) {
                log.error("Error in scheduled task", t);
            } finally {
                long elapsed = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
                RuntimeMetrics.getInstance().recordScheduledTask(
                        elapsed, YamlConfig.config.server.SLOW_TASK_WARNING_MS);
                if (elapsed >= YamlConfig.config.server.SLOW_TASK_WARNING_MS) {
                    log.warn("Scheduled task {} took {} ms", r.getClass().getName(), elapsed);
                }
            }
        }
    }
}
