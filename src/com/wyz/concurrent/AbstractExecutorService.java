package com.wyz.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;


/**
 * @ClassName AbstractExecutorService
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 15:38
 */

public abstract class AbstractExecutorService implements ExecutorService {

    /**
     * 为给定的可运行值和默认值返回{@code RunnableFuture}。
     * @param runnable
     * @param value
     * @param <T>
     * @return
     */
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value){

        return new FutureTask<>(runnable,value);
    }

    /**
     * 创建一个可运行的RunnableFuture
     * @param callable
     * @param <T>
     * @return
     */
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable){

        return new FutureTask<>(callable);
    }

    /**
     *
     * @param task
     * @return
     */
    @Override
    public Future<?> submit(Runnable task){
        //判断task 是否为空
        if (task == null) {
            throw new NullPointerException();
        }
        //将任务包装为FutureTask
        RunnableFuture<Void> ftask = newTaskFor(task, null);
        //运行任务
        execute(ftask);
        //返回运行的ftask对象
        return (Future<?>) ftask;
    }

    /**
     *
     * @param task
     * @param result
     * @param <T>
     * @return
     */
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        //判断task 是否为空
        if (task == null) {
            throw new NullPointerException();
        }
        //将任务包装为FutureTask
        RunnableFuture<T> ftask = newTaskFor(task, result);
        //执行任务
        execute(ftask);
        //返回执行的任务对象
        return (Future<T>) result;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        //判断task 是否为空
        if (task == null) {
            throw new NullPointerException();
        }
        //将任务包装为FutureTask
        RunnableFuture<T> ftask = newTaskFor(task);
        //执行任务
        execute(ftask);
        //返回执行的任务对象
        return (Future<T>) ftask;
    }

    /**
     *
     *
     * @param tasks
     * @param timed
     * @param nanos
     * @param <T>
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks,
                              boolean timed, long nanos)
        throws java.lang.InterruptedException, ExecutionException, TimeoutException {
        //判断任务集合对象是否为null
        if (tasks == null) {
            throw new NullPointerException();
        }
        //获取集合的长度
        int ntasks = tasks.size();
        //判断集合的长度是否为0
        if (ntasks ==0){
            throw new IllegalArgumentException();
        }
        //创建与任务数量等长的容器
        ArrayList<Future<T>> futures = new ArrayList<>(ntasks);

        ExecutorCompletionService<T> ecs = new ExecutorCompletionService<T>(this);

        try {
            //创建ExecutionException 对象，用于存储异常
            ExecutionException ee = null;
            //获取最终期限
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            //迭代器
            Iterator<? extends Callable<T>> it = tasks.iterator();
            //提交任务入队列并执行，将返回用于获取结果的Future对象放在容器中
            futures.add(ecs.submit(it.next()));
            //任务数自减1
            --ntasks;
            //记录活跃的任务数
            int active = 1;
            //自旋（死循环）
            for(;;){
                //获取队列中的头节点
                Future<T> f = ecs.poll();
                //判断头节点是否为空
                if (f == null) {
                    //判断剩余任务数是否大于零
                    if (ntasks > 0) {
                        //任务在自建1
                        --ntasks;
                        //在次提交任务入队列并执行，将返回用于获取结果的Future对象放在容器中
                        futures.add(ecs.submit(it.next()));
                        //活跃的任务数自增1
                        ++active;
                        //如果活跃的任务数为0，结束自旋（死循环出口）
                    }else if(active == 0){
                        break;
                        //判断是否开启时间限制
                    }else if(timed){
                        //阻塞指定时间获取头节点，
                        f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                        //如果获取的Future为空，说明在指定的时间，没有在提交任务。
                        if (f==null) {
                            //抛出超时异常
                            throw new TimeoutException();
                        }
                        //获取到头节点，重新结算超时时间，由此可以看出接口参数为总时间。
                        nanos = deadline - System.nanoTime();
                    }else {
                        //没有timed 为false 说明没有没有超时获取
                        f = ecs.take();
                    }
                }
                //获取到队列的头节点，切值不为空
                if (f != null) {
                    //存活的节点自减1 与 循环中ctive == 0 配合使用。防止无线循环
                    --active;
                    try{
                        //返回结果
                        return f.get();
                    }catch (ExecutionException eex){
                        //记录已知的异常
                        ee = eex;
                    } catch (RuntimeException rex){
                        //将不确定的异常转换为ExecutionException 异常
                        ee = new ExecutionException(rex);
                    }

                }
            }
            //执行到这里说明程序出现异常。
            // 当ee==null的时候说明是在active == 0 还没有获取到结果（实际上ee 不会为null）
            if(ee == null){
                //创建一个ExecutionException 对象
                ee = new ExecutionException();
            }
            //抛出异常
            throw ee;
        }finally {
            //走在这里说明程序已经结束了，中断容器中的任务。
            for (int i = 0, size = futures.size(); i < size; i++) {
                //遍历中断容器中执行的任务。
                futures.get(i).cancel(true);
            }
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException , ExecutionException {
        try {
            return doInvokeAny(tasks,false,0);
        }catch (TimeoutException cannotHappen){
            assert false;
            return null;
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {

        return doInvokeAny(tasks,true,unit.toNanos(timeout));

    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException{
        //判断任务集合是否为空
        if (tasks == null) {
            throw new NullPointerException();
        }
        //创建一个跟任务集合一样长度的Future集合容器
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        //表示为执行完成
        boolean done = false;
        try {
            //遍历所有任务
            for (Callable<T> t : tasks) {
                //为每一个任务创建一个可执行的对象
                RunnableFuture<T> f = newTaskFor(t);
                //将可执行的future对象放入容器中
                futures.add(f);
                //执行任务
                execute(f);
            }
            //走到这一步说明传入的任务都已经执行了，并将对应的返回的future对象放入容器中
            //遍历future容器
            for (int i = 0, size = futures.size(); i <size; i++) {
                //获取当下标下的future对象
                Future<T> f = futures.get(i);
                //判断任务是否执行完成
                if (!f.isDone()) {
                    try {
                        //阻塞，等待任务执行完成
                        f.get();
                        //如果有异常全部忽略，
                    }catch (CancellationException ignore){
                    }catch (ExecutionException ignore){
                    }
                }
            }
            //future集合遍历完了，说明任务都已经执行完了
            //修改标记为true 说明任务都执行完了。
            done = true;
            //返回future集合
            return futures;

        }finally {
            //判断所有的任务是否执行完成
            if(!done){
                //如果没有执行完成。就遍历取消所有任务。
                //注意：cancel(true) 方法只对正在执行的任务有效。
                // 执行完成的任务执行cancel是失效的
                for (int i = 0, size = futures.size(); i <size; i++) {
                    //取消任务
                   futures.get(i).cancel(true);
                }
            }
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException{
        //判断传入的集合是否为空
        if (tasks == null) {
            throw new NullPointerException();
        }
        //将超时的时间统一转换为纳秒值
        long nanos = unit.toNanos(timeout);
        //创建一个与任务数相同长度的容器存储Future
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        //标识所有的任务为执行完毕
        boolean done = false;
        try {
            //遍历任务
            for (Callable<T> task : tasks) {
                //为每个任务创建一个可返回的Future 并放在容器中
                futures.add(newTaskFor(task));
            }
            //计算最终期限
            final long deadline = System.nanoTime() + nanos;
            final int size = futures.size();
            //遍历容器
            for (int i = 0; i < size; i++) {
                //执行当前下标的任务
                execute((Runnable) futures.get(i));
                //重新结算剩余的时间
                nanos = deadline - System.nanoTime();
                //剩余时间小于等于0，说明时间到期了
                if (nanos <= 0L) {
                    //futures 容器，容器中没有被执行的任务get时都会超时。
                    return futures;
                }
            }
            //在次遍历容器。走到这里说名任务都已经执行了。
            for (int i = 0; i < size; i++) {
                //根据当前下标获取future
                Future<T> f = futures.get(i);
                //判断任务是否执行完毕
                if (!f.isDone()) {
                    //说明任务还在执行
                    //判断时间是否到期
                    if (nanos <= 0L) {
                        //到期直接返回futures
                        return futures;
                    }
                    try {
                        //时间没有过期，指定时间（剩余时间）去获取结果。
                        f.get(nanos,TimeUnit.NANOSECONDS);
                        //CancellationException ExecutionException 这两个异常忽略
                    }catch (CancellationException ignore){

                    }catch (ExecutionException ignore){

                    }catch (TimeoutException toe){
                        //获取超时，说明时间到了，直接返回futures
                        return futures;
                    }
                    //重新计算剩余时间，用于上面循环中的获取结果用
                     nanos = deadline - System.nanoTime();

                }
            }
            //走到这里说明所有的任务都已经执行完毕
            done = true;
            return futures;
            
            
        }finally {
            //判断是否正常结束
            if (!done) {
                //说明任务不是正常结束的，是超时结束的，这里需要中断正在执行的任务。
                // 在容器中不能一个一个的去判断每个任务是否还在执行，这里采取的是全部取消，方式简单粗暴。
                // 因为已经结束的任务，取消操作时无效的
                for (int i = 0, size = futures.size(); i < size; i++) {
                    futures.get(i).cancel(true);
                }
            }
        }
    }
}
