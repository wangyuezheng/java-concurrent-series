package com.wyz.concurrent;

import com.sun.istack.internal.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;


/**
 * @ClassName ExecutorService
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 15:22
 */

public interface ExecutorService extends Executor {
    /**
     *  关闭
     */
    void shutdown();

    /**
     *
     * @return
     */
    @NotNull
    List<Runnable> shutdownNow();

    boolean isShutdown();

    /**
     *
     * @return
     */
    boolean isTerminated();

    /**
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    boolean awaitTermination(long timeout, @NotNull TimeUnit unit)
            throws InterruptedException;

    /**
     *
     * @param task
     * @param <T>
     * @return
     */
    @NotNull
    <T> Future<T> submit(@NotNull Callable<T> task);

    /**
     *
     * @param task
     * @param result
     * @param <T>
     * @return
     */
    @NotNull
    <T> Future<T> submit(@NotNull Runnable task, T result);

    /**
     *
     * @param task
     * @return
     */
    @NotNull
    Future<?> submit(@NotNull Runnable task);

    /**
     *
     * @param tasks
     * @param <T>
     * @return
     * @throws InterruptedException
     */
    @NotNull
    <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks)
            throws InterruptedException;

    /**
     *
     * @param tasks
     * @param timeout
     * @param unit
     * @param <T>
     * @return
     */
    @NotNull
    <T> List<Future<T>> invokeAll(@NotNull Collection<? extends  Callable<T>> tasks,
                                  long timeout, @NotNull TimeUnit unit)
            throws InterruptedException;

    /**
     *
     * @param tasks
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @NotNull
    <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException;


    /**
     *
     * @param tasks
     * @param timeout
     * @param unit
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks,
                    long timeout,@NotNull TimeUnit unit)
            throws InterruptedException,ExecutionException, TimeoutException;




}
