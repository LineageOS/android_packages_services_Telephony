/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An implementation of ExecutorService that just runs the requested task on the thread that it
 * was called on for testing purposes.
 */
public class TestExecutorService implements ScheduledExecutorService {

    private static class CompletedFuture<T> implements Future<T>, ScheduledFuture<T> {

        private final Callable<T> mTask;
        private final long mDelayMs;

        CompletedFuture(Callable<T> task) {
            mTask = task;
            mDelayMs = 0;
        }

        CompletedFuture(Callable<T> task, long delayMs) {
            mTask = task;
            mDelayMs = delayMs;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return mTask.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return mTask.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            if (unit == TimeUnit.MILLISECONDS) {
                return mDelayMs;
            } else {
                // not implemented
                return 0;
            }
        }

        @Override
        public int compareTo(Delayed o) {
            if (o == null) return 1;
            if (o.getDelay(TimeUnit.MILLISECONDS) > mDelayMs) return -1;
            if (o.getDelay(TimeUnit.MILLISECONDS) < mDelayMs) return 1;
            return 0;
        }
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
        return null;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return false;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return new CompletedFuture<>(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Future<?> submit(Runnable task) {
        task.run();
        return new CompletedFuture<>(() -> null);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
            TimeUnit unit) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        // No need to worry about delays yet
        command.run();
        return new CompletedFuture<>(() -> null, delay);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return new CompletedFuture<>(callable, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
            TimeUnit unit) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
