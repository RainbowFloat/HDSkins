package com.minelittlepony.hdskins.util;

import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;

/**
 * A helper class to watch a file. Callbacks can be registered to a {@link WatchEvent.Kind kind}. Kind registration is
 * automatically resolved based on registered callbacks. To create an instance, use the {@link Builder}.
 *
 * <p>Example:</p>
 *
 * <pre>
 *     Watcher&lt;Path&gt; watcher = new Watcher.Builder&lt;Path&gt;()
 *            .watch(someDirectory)
 *            .on(StandardWatchEventKinds.ENTRY_MODIFY, e -> {
 *                Path p = e.context();
 *                // p was modified
 *            }).build();
 * </pre>
 *
 * <p>Watcher implements Closeable, so remember to call {@link #close()} after it is no longer needed.</p>
 *
 * @param <T> The type of watchable. E.g {@link java.nio.file.Path Path}
 */
public class Watcher<T extends Watchable> extends Thread implements Closeable {
    private static final Logger logger = LogManager.getLogger();
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    private final T watched;
    private final Map<WatchEvent.Kind<T>, Consumer<WatchEvent<T>>> listeners;

    private final WatchService watcher;

    private Watcher(T watched, Map<WatchEvent.Kind<T>, Consumer<WatchEvent<T>>> listeners) throws IOException {
        this.watched = watched;
        this.listeners = ImmutableMap.copyOf(listeners);

        watcher = FileSystems.getDefault().newWatchService();

        watched.register(watcher, listeners.keySet().toArray(new WatchEvent.Kind[0]));

        this.setName("File Watcher Thread #" + THREAD_COUNT.getAndIncrement());
        this.setDaemon(true);
        this.start();
        logger.info("Starting file watcher for {}", watched);
    }

    @Override
    public void run() {
        try {
            while (true) {

                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException ex) {
                    return;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    //noinspection unchecked
                    WatchEvent<T> e = (WatchEvent<T>) event;
                    WatchEvent.Kind<T> kind = e.kind();

                    // send the event to the listeners
                    if (listeners.containsKey(kind)) {
                        listeners.get(kind).accept(e);
                    }
                }

                // The key needs to be reset each iteration.
                if (!key.reset()) {
                    break;
                }

            }
        } catch (ClosedWatchServiceException e) {
            logger.debug("File watcher for {} has been closed", watched);
        }
    }

    @Override
    public void close() throws IOException {
        watcher.close();
    }

    public static class Builder<T extends Watchable> {

        private T watched;
        private Map<WatchEvent.Kind<T>, Consumer<WatchEvent<T>>> listeners = new IdentityHashMap<>();

        /**
         * Sets the object to watch. If it is a Path, it cannot be a file.
         *
         * @param watched The watched object
         * @return This builder
         */
        public Builder<T> watch(T watched) {
            this.watched = watched;
            return this;
        }

        /**
         * Registers a callback to a {@link WatchEvent.Kind}. Multiple unique kinds can be used.
         *
         * @param kind     The watch event kind
         * @param listener The callback to register
         * @return This builder
         */
        public Builder<T> on(WatchEvent.Kind<T> kind, Consumer<WatchEvent<T>> listener) {
            listeners.put(kind, listener);
            return this;
        }

        /**
         * Builds and starts the watcher and accompanying thread.
         *
         * @return The watcher instance
         * @throws IOException           If a WatchService could not be created or the watchable object could not be
         *                               registered with it
         * @throws IllegalStateException If the builder is incomplete
         */
        public Watcher<T> build() throws IOException {
            checkState(watched != null, "Watch path cannot be null");
            checkState(!listeners.isEmpty(), "Cannot listen to nothing");
            return new Watcher<>(watched, listeners);
        }
    }
}
