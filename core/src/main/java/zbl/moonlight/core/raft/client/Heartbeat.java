package zbl.moonlight.core.raft.client;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class Heartbeat implements Runnable {
    private final static int HEARTBEAT_INTERVAL_MILLIS = 60;

    private final RaftClient raftClient;
    private final DelayQueue<HeartbeatTask> delayQueue = new DelayQueue<>();
    private boolean shutdown = false;

    public Heartbeat(RaftClient raftClient) {
        this.raftClient = raftClient;
        delayQueue.offer(new HeartbeatTask(HEARTBEAT_INTERVAL_MILLIS));
    }

    static class HeartbeatTask implements Delayed {
        private final long time;

        private HeartbeatTask(long delayTime) {
            this.time = delayTime + System.currentTimeMillis();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long delta = time - System.currentTimeMillis();
            return unit.convert(delta, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.time, ((HeartbeatTask) o).time);
        }
    }

    public void shutdown() {
        shutdown = true;
    }

    @Override
    public void run() {
        while (!shutdown) {
            try {
                delayQueue.take();
                raftClient.interrupt();
                delayQueue.offer(new HeartbeatTask(HEARTBEAT_INTERVAL_MILLIS));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
