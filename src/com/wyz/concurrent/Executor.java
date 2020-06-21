package com.wyz.concurrent;

import com.sun.istack.internal.NotNull;

/**
 * @ClassName Executor
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 15:19
 */

public interface Executor {

    /**
     * 在将来的某个时候执行给定的命令。该命令可以在新线程中执行，可以在合用线程中执行，
     * 也可以在调用线程中执行，由{@code Executor}实现决定。
     * @param command
     */
    void execute(@NotNull Runnable command);

}
