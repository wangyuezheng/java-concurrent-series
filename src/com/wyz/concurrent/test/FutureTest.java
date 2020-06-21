package com.wyz.concurrent.test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @ClassName FutureTest
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 15:03
 */

public class FutureTest {
    public static void main(String[] args) {

        FutureTask<Integer> task = new FutureTask<>(()->{
            Thread.sleep(2000);
            return 10;
        });
        new Thread(task).start();
        try {
            Integer integer = task.get(1, TimeUnit.SECONDS);
            System.out.println("===" + integer);
        } catch (InterruptedException e) {
            System.err.println("计算中断");
            e.printStackTrace();
        } catch (ExecutionException e) {
            System.err.println("计算异常");
            e.printStackTrace();
        } catch (TimeoutException e) {
            System.err.println("超时。。。。");
            e.printStackTrace();
        }
    }

}
