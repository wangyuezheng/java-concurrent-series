package com.wyz.concurrent;


import com.sun.istack.internal.NotNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * 一个{@code Future}表示一个异步的结果
 * 计算。方法提供检查计算是否
 * 完成，等待其完成，并检索的结果
 * 的计算。只能使用方法检索结果
 * {@code get}当计算完成时，阻塞if
 * 必要的，直到它准备好。属性执行取消操作
 * {@code cancel}方法。提供了其他方法
 * 确定任务是否正常完成或已取消。一次
 * 计算已完成，无法取消计算。
 * 如果你想使用一个{@code Future}
 * 但不能提供一个可用的结果，你可以
 * 声明形式{@code Future<?>},
 * 作为底层任务的结果返回{@code null}。
 *
 <p>
 * <b>示例使用</b> (注意，下面的类都是虚构的.)
 * <pre> {@code
 * interface ArchiveSearcher { String search(String target); }
 * class App {
 *   ExecutorService executor = ...
 *   ArchiveSearcher searcher = ...
 *
 *   void showSearch(final String target)
 *       throws InterruptedException {
 *     Future<String> future
 *       = executor.submit(new Callable<String>() {
 *         public String call() {
 *             return searcher.search(target);
 *         }});
 *     displayOtherThings(); // 在搜索时做其他事情
 *     try {
 *       displayText(future.get()); // use future
 *     } catch (ExecutionException ex) { cleanup(); return; }
 *   }
 * }}</pre>
 *
 *{@code FutureTest}类是一个实现的{@code Future}
 * 实现{@code Runnable}，因此可以由{@code Executor}执行。
 * 例如，上面使用{@code submit}的构造可以替换为:
 <pre> {@code
 * FutureTest<String> future =
 *   new FutureTest<String>(new Callable<String>() {
 *     public String call() {
 *       return searcher.search(target);
 *   }});
 * executor.execute(future);
 * }
 * </pre>
 *<p>内存一致性效应:异步计算所采取的动作
 * < a href = " package-summary。html # MemoryVisibility " > <i>happen-before</i></a>
 * 在另一个线程中跟随对应的{@code Future.get()}的操作。
 * @see java.util.concurrent.FutureTask
 * @see java.util.concurrent.Executor
 * @author wangyuezheng
 * @param <V>  Future 的get() 方法返回的结果类型
 * */

public interface Future<V> {
    /**
     * 试图取消此任务的执行。
     * 如果任务已经完成或取消，这种尝试将则失败，
     * 调用{@code cancel}时，该任务还没有启动，
     * 不应该运行此任务。如果任务已经开始，
     * 然后由{@code mayInterruptIfRunning}参数确定
     * 执行此任务的线程是否应在
     * 试图停止该任务。
     *
     * 在此方法返回后，
     * 后续调用{@link #isDone}将执行此操作 总是返回{@code true}。(有结果返回说明该任务执行完成)
     * 后续调用{@link #isCancelled}
     * 如果这个方法返回{@code true}，它将始终返回{@code true}。（说明任务已经被取消）
     *
     * @param mayInterruptIfRunning {@code true}执行此任务的线程应该被中断;
     *                                          否则，允许进行中的任务完成
     * @return {@code true} 表示成功中断正在执行的任务;
     * {@code false} 表示中断操作失败。通常失败的原因是任务已经执行完成。
     */
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * 如果该任务在正常完成之前被取消，则返回{@code true}。
     * @return
     */
    boolean isCancelled();

    /**
     * 方法是否执行完毕
     *
     * 如果任务完成，返回{@code true}。
     * 任务完成：可能是由于正常终止、异常或
     * 取消——在所有这些情况下，这个方法将返回
     * }{@code true}.
     * @return
     */
    boolean isDone();

    /**
     * 如果任务还在执行，需要等待任务执行完成，然后将结果返回。
     * @return 返回计算的结果
     * @throws CancellationException 如果任务被取消，抛出
     * @throws InterruptedException 如果当前线程在等待时被中断，抛出
     * @throws ExecutionException 如果计算抛出异常
     */
    V get() throws InterruptedException, ExecutionException;

    /**
     * 如果任务还在执行，需要等待任务执行，但是最多只等待指定的时间。
     * 在指定的时间范围内，任务执行完成，返回计算的结果。否则抛出TimeoutException异常
     *
     * @param timeout 等待的最大时间
     * @param unit 超时参数的时间单位
     * @return 返回计算结果
     * @throws CancellationException 如果任务被取消，抛出
     * @throws InterruptedException 如果当前线程在等待时被中断，抛出
     * @throws ExecutionException 如果计算抛出异常
     * @throws TimeoutException 如果等待超时,抛出
     */
    V get(long timeout, @NotNull TimeUnit unit) throws InterruptedException,
            ExecutionException, TimeoutException;

}
