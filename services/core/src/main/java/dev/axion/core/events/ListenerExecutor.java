package dev.axion.core.events;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Where listeners of domain events run (events and webhooks design, section 4.3): 32 virtual threads
 * and an unbounded queue, so a burst of events waits here and never holds the request thread that
 * committed it. On shutdown, running listeners get a few seconds to finish; what is left stays
 * incomplete in the registry and is resubmitted on the next start.
 */
@Component
class ListenerExecutor implements AsyncConfigurer, DisposableBean {

    static final int THREADS = 32;

    private static final int SHUTDOWN_GRACE_SECONDS = 10;

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    ListenerExecutor() {
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setThreadFactory(Thread.ofVirtual().name("event-listener-", 1).factory());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS);
        executor.initialize();
    }

    @Override
    public Executor getAsyncExecutor() {
        return executor;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
