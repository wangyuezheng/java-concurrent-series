package com.wyz.concurrent.test;

/**
 * @ClassName Test
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/16 13:14
 */

public class Test {
   static final int CAPACITY = (1 << 29) - 1;
    public static void main(String[] args) {
/*        System.out.println(-1 << 29);
        System.out.println(0 << 29);
        System.out.println(1 << 29);
        System.out.println(2 << 29);
        System.out.println(3 << 29);

        System.out.println("===========================");

        System.out.println(-1 & ~((1 << 29) - 1));
        System.out.println(0 & ~((1 << 29) - 1));
        System.out.println(1 & ~((1 << 29) - 1));
        System.out.println(2 & ~((1 << 29) - 1));
        System.out.println(3 & ~((1 << 29) - 1));*/


        System.out.println(">>>>>>>>>>>>>>>>");

        System.out.println(-1 & CAPACITY);
        System.out.println(0 & CAPACITY);
        System.out.println(1 & CAPACITY);
        System.out.println(2 & CAPACITY);
        System.out.println(3 & CAPACITY);

        System.out.println("---------------");
        /**
         *
         * 相当于
         *  -536870912 + 0 = -536870912
         *  -536870912 + 1 = -536870911
         *  -536870912 + 2 = -536870910
         *  -536870912 + 3 = -536870909
         */
        System.out.println(-536870912 | 3);


    }
}
