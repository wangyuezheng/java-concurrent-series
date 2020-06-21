package com.wyz.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName ExecutorCompletionService
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 15:58
 */

public class ExecutorCompletionService<V> implements CompletionService<V> {
    private final Executor executor;
    private final AbstractExecutorService aes;
    private final BlockingQueue<Future<V>> completionQueue;

    /**
     * FutureTask extension to enqueue upon completion
     */
    private class QueueingFuture extends FutureTask<Void> {
        QueueingFuture(RunnableFuture<V> task) {
            super(task, null);
            this.task = task;
        }

        @Override
        protected void done() {
            completionQueue.add(task);
        }

        private final Future<V> task;
    }

    private RunnableFuture<V> newTaskFor(Callable<V> task) {
        if (aes == null) {
            return new FutureTask<V>(task);
        } else {
            return aes.newTaskFor(task);
        }
    }

    private RunnableFuture<V> newTaskFor(Runnable task, V result) {
        if (aes == null) {
            return new FutureTask<V>(task, result);
        } else {
            return aes.newTaskFor(task, result);
        }
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and a
     * {@link LinkedBlockingQueue} as a completion queue.
     *
     * @param executor the executor to use
     * @throws NullPointerException if executor is {@code null}
     */
    public ExecutorCompletionService(Executor executor) {
        if (executor == null) {
            throw new NullPointerException();
        }
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
                (AbstractExecutorService) executor : null;
        this.completionQueue = new LinkedBlockingQueue<Future<V>>();
    }


    public ExecutorCompletionService(Executor executor,
                                     BlockingQueue<Future<V>> completionQueue) {
        if (executor == null || completionQueue == null){
            throw new NullPointerException();
        }
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
                (AbstractExecutorService) executor : null;
        this.completionQueue = completionQueue;
    }

    @Override
    public Future<V> submit(Callable<V> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        RunnableFuture<V> f = newTaskFor(task);
        executor.execute(new ExecutorCompletionService.QueueingFuture(f));
        return f;
    }

    @Override
    public Future<V> submit(Runnable task, V result) {
        if (task == null) {
            throw new NullPointerException();
        }
        RunnableFuture<V> f = newTaskFor(task, result);
        executor.execute(new ExecutorCompletionService.QueueingFuture(f));
        return f;
    }

    @Override
    public Future<V> take() throws java.lang.InterruptedException{
        return completionQueue.take();
    }

    @Override
    public Future<V> poll() {
        return completionQueue.poll();
    }

    @Override
    public Future<V> poll(long timeout, TimeUnit unit)
            throws java.lang.InterruptedException {
        return completionQueue.poll(timeout, unit);
    }
}