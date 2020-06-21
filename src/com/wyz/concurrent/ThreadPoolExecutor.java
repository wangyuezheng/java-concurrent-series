package com.wyz.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @ClassName ThreadPoolExecutor
 * @Description //TODO
 * @Author wangyuezheng
 * @Date 2020/6/15 19:50
 */

public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * 主池控制状态ctl是封装了两个概念性字段的原子整数
     * workerCount，表示线程的有效数量
     *  runState，指示是否运行、关闭等
     *
     *  为了将它们压缩到一个int中，我们将workerCount限制为(2^29)-1(大约5亿个)线程，
     *  而不是(2^31)-1(20亿)线程。如果将来出现这个问题，可以将变量改为AtomicLong，
     *  并调整下面的shift/mask常量。但是，除非有需要，否则使用int会更快、更简单一些。
     *
     *  workerCount是允许运行和不允许停止的worker数。该值可能与活动线程的实际数量暂时不同，
     *  例如，当线程工厂在请求时未能创建线程，以及退出线程在终止之前仍在执行时。
     *  用户可见的池大小被报告为工作者设置的当前大小。
     *
     *  runState 提供了主生命周期控制，其值为:
     *      RUNNING:接受新任务并处理已排队的任务
     *      SHUTDOWN:不接受新任务，但处理已排队的任务
     *      STOP:不接受新任务，不处理队列任务，中断正在执行的任务
     *      TIDYING:所有任务已经终止，workerCount为0，转换到状态整理的线程将运行terminated()钩子方法
     *      TERMINATED: TERMINATED()已经完成
     *
     *  为了允许有序的比较，这些值之间的数字顺序很重要。
     *  运行状态单调地随时间增加，但不需要达到每个状态。转换:
     *
     *  RUNNING -> SHUTDOWN  对shutdown()的调用，可能在finalize()中隐式调用
     *  (RUNNING or SHUTDOWN) -> STOP  关于调用shutdownNow()
     *  SHUTDOWN -> TIDYING  当队列和池都为空时
     *  STOP -> TIDYING  当池是空的
     *  TIDYING -> TERMINATED  当terminate()钩子方法完成时
     *
     *  等待awaitTermination()的线程将在状态达到TERMINATED时返回。
     *
     *  检测从关机到整理的过渡更少
     *  比你想的要简单，因为队列可能会变成
     *  空后非空，反之亦然，关机状态，但
     *  我们只能在看到它是空的时候终止
     *  workerCount为0(有时需要重新检查——见下文)。
     *
     */

    /**
     *  ctl 初始化得时候 值为RUNNING（-536870912）值 也就是说明线程池最大允许536870912个线程
     *  当值为0时，线程池就处于SHUTDOWN 状态，这时就不能在提交任务了，只能处理队列中得任务
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    /**
     * COUNT_BITS = 32 - 3 = 29  为了将它们压缩到一个int中，
     * 我们将workerCount限制为(2^29)-1(大约5亿个)线程，
     *  而不是(2^31)-1(20亿)线程。
     */
    private static final int COUNT_BITS = Integer.SIZE - 3;
    /**
     * 容量为 (2^29)-1 线程池得最大容量
     */
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    /**
     *  运行得状态的起始值
     *   -1 << 29 = -536870912
     */
    private static final int RUNNING    = -1 << COUNT_BITS;
    /**
     *  关闭状态的起始值
     * 0 << 29 = 0
     */
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    /**
     *  停止的状态起始值
     * 1 << 29 = 536870912
     */
    private static final int STOP       =  1 << COUNT_BITS;
    /**
     * 处于整理状态的起始值
     * 2 << 29 = 1073741824
     */
    private static final int TIDYING    =  2 << COUNT_BITS;
    /**
     * 处于终止状态的起始值
     * 3 << 29 = 1610612736
     */
    private static final int TERMINATED =  3 << COUNT_BITS;

    /**
     *   根据当前的workerCount 换算运行状态
     *
     * @param c 当c < 0  时 一直返回-536870912 ，说明线程池处于RUNNING状态。
     *           当c >= 0 , 且 c < 536870912 时  返回0 表示 线程池处于SHUTDOWN状态
     *           当c >= 536870912 , 且 c < 1073741824 时，返回 536870912 表示 处于STOP状态
     *           当c >= 1073741824 ,且 c < 1610612736 时，返回 1073741824 表示处于TIDYING状态
     *           当c >= 1610612736 ,且 c < 2147483648 时，返回 1610612736 表示处于TERMINATED状态
     * @return
     */
    private static int runStateOf(int c)     {
        return c & ~CAPACITY;
    }

    /**
     *  获取每个状态下运行的workerCount值
     *
     * @param c
     *      1、当 0 > c >=-536870912 时 值随着c的自增从0 自增到 536870911 即：
     *              当c=-536870912 时，值为0
     *              当c=-536870911 时，值为1
     *              当c=-536870910 时，值为2
     *                    ....
     *              当c = -1 时，值为536870911
     *      2、当 536870912 > c >=0 时 值随着c的自增从0 自增到 536870911 即：
     *                 当c=0 时，值为0
     *                 当c=1 时，值为1
     *                 当c=2 时，值为2
     *                 ....
     *                 当c=536870911时，值为 536870911
     *       3、当 1073741824 > c >=536870912 时 值随着c的自增从0 自增到 536870911
     *
     *       4、当 1610612736 > c >=1073741824 时 值随着c的自增从0 自增到 536870911
     *
     *       5、当 2147483648 > c >=1610612736 时 值随着c的自增从0 自增到 536870911
     * @return
     */
    private static int workerCountOf(int c)  {
        return c & CAPACITY;
    }

    /**
     * // TODO 待确认
     *  rs + wc
     * @param rs 修改线程池状态对应的起始值
     * @param wc workerCount值
     * @return
     */
    private static int ctlOf(int rs, int wc) { return rs | wc; }


    /*
     * 不需要解压缩ctl的位域访问器。
     * 这取决于位布局和workerCount永远不会是负的。
     */

    /**
     * 运行的线程数是否小于 指定的值
     * @param c ctl中运行的线程值
     * @param s
     * @return
     */
    private static boolean runStateLessThan(int c ,int s){
        return c < s;
    }

    /**
     * 运行的线程数是否大于等于指定的值
     * @param c ctl 中运行的线程值
     * @param s
     * @return
     */
    private static boolean runStateAtLeast(int c, int s) {

        return c >= s;
    }

    /**
     *
     *   判断线程池是否是运行状态
     *   返回true 表示正常运行
     *   返回false 表示处于非正常运行的状态
     *
     * @param c ctl 中的值，
     * @return true: ctl中记录的线程数小于SHUTDOWN时说明线程还没有达到最大允许的值，是运行状态
     *          false: 说明已经达到线程池容纳的最大线程数，线程池不能在接收任务。
     */
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }



    /**
     * 将ctl中的值 CAS 方式自增1
     *
     * @param expect  expect = ctl.get();
     * @return
     */
    private boolean compareAndIncrementWorkerCount(int expect){
        return ctl.compareAndSet(expect,expect + 1);
    }

    /**
     * 将ctl中的值 CAS 方式缩减1
     * @param expect expect = ctl.get();
     * @return
     */
    private boolean compareAndDecrementWorkerCount(int expect){
        return ctl.compareAndSet(expect,expect - 1);
    }

    /**
     * ctl的值自减一，
     * 在线程执行异常情况下（参见processWorkerExit），或者执行getTask方法是线程池不在运行状态会调用该方法
     *
     */
    private void decrementWorkerCount(){
        do{}while (!compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * 翻译：
     *  用于保存任务并将其传递给工作线程的队列。
     *  我们不要求workQueue.poll()返回null就一定意味着workQueue.isEmpty()，
     *  因此只依赖isEmpty来查看队列是否为空(例如，在决定是否从关闭转换到清理时，我们必须这样做)。
     *  这适用于特殊用途的队列，比如允许poll()返回null的DelayQueues，
     *  即使在延迟到期后它可能返回非null。
     */

    /**
     * 用于保存核心线程来不及处理的任务。当线程有空闲的时候将任务传递给线程执行。
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 翻译：
     *  锁对workers 的存取设置和相关簿记。
     *  虽然我们可以使用某种类型的并发集，但使用锁通常更可取。其中一个原因是，
     *  这会序列化interruptIdleWorkers，从而避免不必要的中断，特别是在关机期间。
     *  否则，已经退出的线程将并发地中断那些尚未中断的线程。
     *  它还简化了一些与largestPoolSize等相关的统计簿记。
     *  我们现在也对shutdown and shutdownNow 持有mainLock，同时分别检查允许中断和实际中断，
     *  以确保workers设置是稳定的。
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * 设置包含池中所有Worker线程。仅在持有主锁时（mainLock）访问。
     *
     */
    private final HashSet<Worker> workers = new HashSet<>();

    /**
     * 等待条件，以支持等待终止
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * 跟踪获得的最大池大小。仅在主锁下访问。
     * 用于记录线程池最大线程的值。仅在主锁下访问（mainLock）。
     */
    private int largestPoolSize;

    /**
     * 已完成任务的计数器。仅在工作线程终止时更新。仅在主锁下访问（mainLock）。
     */
    private long completedTaskCount;


    /*
     * 所有用户控制参数都声明为volatile，因此正在进行的操作是基于最新的值，
     * 但不需要锁定，因为没有内部不变量依赖于它们与其他操作同步变化。
     */
    /**
     * 翻译：
     * Factory for new threads。所有线程都是使用这个工厂创建的(通过方法addWorker)。
     * 所有调用方都必须为addWorker失败做好准备，
     * 这可能因为系统或用户使用的策略限制了创建线程的数量。
     * 即如果没有将其视为错误，在创建线程失败时，可能会导致拒绝新任务或现有任务仍然停留在队列中。
     *
     * 甚至在遇到OutOfMemoryError之类的错误时，我们还会进一步保留池不变量，
     * 这些错误可能会在尝试创建线程时抛出。
     * 由于需要在Thread.start中分配本机堆栈，因此此类错误相当常见。
     * 用户将希望执行清理池关闭以进行清理。
     * 可能会有足够的内存可用来完成清理代码，而不会遇到另一个OutOfMemoryError错误。
     */


    /**
     * 创建线程工厂
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 处理程序在执行中饱和或关闭时调用。
     */
    /**
     * 线程池拒绝提交任务的策略类。
     * 在线程池队列已满切已经达到最大线程数时或者线程池关闭时调用。
     */
    private volatile RejectedExecutionHandler handler;


    /**
     *  keepAliveTime 表示空闲线程等待work的存活时间（时间单位为纳秒）
     * 当存在的线程超过corePoolSize或者allowCoreThreadTimeOut时，
     * 线程使用这个超时。超过这个时间将被回收。
     * 否则，不会使用，他们永远等待新工作。
     */

    /**
     *  1、当allowCoreThreadTimeOut = false 时。说明核心线程没有超时等待任务的限制，会永远存活等待任务，
     * 只对于超过核心线程数的线程使用。在keepAliveTime 时间范围内没有获取到任务，该线程将被中断回收。
     * 也就是说即使长时间没有任务提交，线程池最后也不会回收核心线程。
     *
     *  2、当allowCoreThreadTimeOut = true 时。表示核心线程空闲的时间超过keepAliveTime，也会被回收。
     *  也就是说当长时间没有任务提交时。线程池中运行的线程为0
     */
    private volatile long keepAliveTime;

    /**
     * 如果为false(默认)，则核心线程即使空闲时也保持活动。
     * 如果为true，核心线程使用keepAliveTime来超时等待工作。
     * allowCoreThreadTimeOut 该值用于核心线程。
     *
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * 核心线程数：
     *  corePoolSize 是workers 线程存活的最小数量(不允许超时等)，
     *  除非allowCoreThreadTimeOut被设置true，在这种情况下，最小值为零。
     */
    private volatile int corePoolSize;

    /**
     *  最大线程数：
     *  注意，实际的最大值在内部受到容量的限制
     *
     */
    private volatile int maximumPoolSize;

    /**
     * 默认的拒绝提交任务策略。直接抛出异常来拒绝提交任务
     *
     */
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    /**
     *  执行shutdown and shutdownNow.是需要权限。
     *  我们还需要(参见checkShutdownAccess)调用者拥有实际中断工作集中
     *  (由Thread.interrupt管理，它依赖于ThreadGroup。
     *  checkAccess，这又依赖于SecurityManager.checkAccess)线程的权限。
     *  只有当这些检查通过时才尝试关闭。
     */
    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");
    /**
     * 执行finalizer时 使用的上下文，或null。
     */
    private final AccessControlContext acc;

    /**
     *Class Worker主要维护线程运行任务的中断控制状态，以及其他次要的记帐。
     * 这个类扩展了AbstractQueuedSynchronizer以简化获取和释放围绕每个任务执行的锁。
     * 这样可以防止中断正在运行的任务，而不是唤醒等待任务的工作线程。
     * 我们实现了一个简单的不可重入互斥锁，而不是使用ReentrantLock，
     * 因为我们不希望工作任务在调用诸如setCorePoolSize之类的池控制方法时能够重新获得锁。
     * 此外，为了在线程真正开始运行任务之前抑制中断，我们将锁状态初始化为负值，
     * 并在启动时清除它(在runWorker中)。
     */
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable{

        private static final long serialVersionUID = 6138294804551838833L;
        /**
         *  运行worker的线程。该线程是由ThreadFactory 创建，如果创建失败，则返回null
         */
        final Thread thread;

        /**
         * 要运行的初始任务。可能是null。
         */
        Runnable firstTask;

        /**
         * 线程完成任务计数器
         * 当一个任务执行完成时 值自增1
         */
        volatile long completedTasks;

        /**
         * 构造方法
         * @param firstTask 运行初始化任务。可能为null
         */
        Worker(Runnable firstTask){
            //防止在运行任务之前被中断
            setState(-1);
            this.firstTask = firstTask;
            //通过工厂创建线程
            this.thread = getThreadFactory().newThread(this);
        }

        @Override
        public void run() {
            // todo 将主运行循环委托给外部runWorkerr
            runWorker(this);
        }

        //锁的方法
        //值0表示未锁状态。
        //值1表示锁定状态。

        /**
         * 判断是否加锁
         *   值0表示未锁状态。
         *   值1表示锁定运行任务状态。
         *   值-1 表示锁定未运行状态
         * @return true:表示已锁定，false: 表示未锁
         */
        @Override
        protected boolean isHeldExclusively(){
            //获取AQS中的state 值为0 表示未锁
            return getState() !=0;
        }

        /**
         *  尝试获取锁，这个获取锁失败，不会添加到队列里阻塞。
         * @param unused 参数值没有意义
         * @return true 表示获取锁成功
         *          false 表示获取锁失败
         */
        @Override
        protected boolean tryAcquire(int unused){
            //cas 将state值修改为1 成功说明加锁成功
            if (compareAndSetState(0,1)) {
                //将当前线程赋值给exclusiveOwnerThread 表示独享锁（AQS中的方法）
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        /**
         * 释放锁资源
         * @param unused 参数值没有意义
         * @return
         */
        @Override
        protected boolean tryRelease(int unused){
            //exclusiveOwnerThread设置为null 表示当前没有线程独享锁
            //此方法不强制任何同步或{@code volatile}字段修饰。
            setExclusiveOwnerThread(null);
            //将state设置为0 未锁。
            setState(0);
            return true;
        }

        /**
         * 加锁，加锁失败会添加到队列里阻塞
         */
        public void lock(){
            //阻塞获取锁（AQS中的方法）
            acquire(1);
        }

        /**
         * 尝试加锁，尝试锁失败不会阻塞
         * 里面调用的是本地的tryAcquire(unused)
         * @return
         */
        public boolean tryLock(){
            //调用本地的tryAcquire方法
            return tryAcquire(1);
        }

        /**
         * 释放锁
         */
        public void unlock(){
            //释放锁（AQS中的方法）并唤醒队列中的一个任务
            release(1);
        }

        /**
         * 判断是否加锁了
         * @return
         */
        public boolean isLocked(){
            //本地方法 查看AQS中的 state是否不等于0
            return isHeldExclusively();
        }

        /**
         * 如果线程获取锁，并且在执行状态就中断，反之不操作
         */
        void interruptIfStarted(){
            Thread t;
            //如果线程获取锁，且worker中的线程不为空，同时 该线程没有被中断
            if(getState() >=0 && (t = thread) != null && !t.isInterrupted()){
                try {
                    //中断该线程 中断抛出SecurityException则忽略，说明有该线程没有被中断
                    t.interrupt();
                }catch (SecurityException ignore){
                    //忽略异常
                }
            }
        }
    }

    /*
     * 用于设置控件状态的方法
     *
     */

    /**
     * 当运行的状态要大于等于给定的状态值 将运行状态转换到给定的状态，
     *    反之，则不影响它。
     * @param targetState 值为，SHUTDOWN 或者STOP中的一个
     *                    (不是TIDYING 或 TERMINATED——使用tryTerminate)
     */
    private void advanceRunState(int targetState){
        //自旋 修改状态值
        for(;;){
            //获取线程池中状态值
            int c = ctl.get();
            //判断是否是非运行状态 ，如果是运行状态则自旋阻塞，一直自旋到满足条件
            if (runStateAtLeast(c,targetState)) {
                //将状态值修改为为指定的状态，并将当前的workerCount值与这个状态绑定
                ctl.compareAndSet(c,ctlOf(targetState,workerCountOf(c)));
                //结束自旋，退出
                break;
            }
        }
    }

    /**
     *
     *      如果线程池状态为SHUTDOWN 状态同时线程池和队列都为空，或者 线程池为STOP状态且线程池为空
     * 则转换到TERMINATED状态
     *      另外，如果workerCount非零，但符合终止条件，则中断空闲的worker以确保关闭信号传播。
     *      必须在任何可能使终止成为可能的操作(减少工作人员数量或在关闭期间从队列中删除任务)
     *   之后调用此方法。该方法是非私有的，允许从ScheduledThreadPoolExecutor访问。
     */
    final void tryTerminate(){
        //自旋
        for(;;){
            //获取当前线程池状态值
            int c = ctl.get();
            //如果线程池处于RUNNING 则不处理。

            //如果线程池处于SHUTDOWN 且 线程队列不为空时 不处理
            //如果线程池处于TIDYING或TERMINATED 则不处理




            if (isRunning(c) ||
                    runStateAtLeast(c,TIDYING) ||
                    (runStateOf(c)==SHUTDOWN && !workQueue.isEmpty())) {
                return;
            }
            //当线程池为SHUTDOWN 且队列为空需要处理
            //有资格终止
            //当线程池为SHUTDOWN  且workerCount值非零0
            if (workerCountOf(c) != 0) {
                //（默认）中断一个任务
                interruptIdleWorkers(ONLY_ONE);
                //中断成功返回
                return;
            }
            //当线程池为SHUTDOWN  且workerCount值为0
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                //修改线程池的状态为TIDYING workerCount值设置为0
                if (ctl.compareAndSet(c,ctlOf(TIDYING,0))) {
                    try {
                        //终止线程池，本类中该方法没有做任何实现，
                        // 如果需要额外的功能，可以在子类来覆盖该方法
                        terminated();
                    }finally {
                        //修改状态为TERMINATED 终止
                        ctl.set(ctlOf(TERMINATED,0));
                        //唤醒termination下所有阻塞的线程
                        termination.signalAll();
                    }
                    return;
                }
            }finally {
                //释放锁
                mainLock.unlock();
            }
            ////修改线程池的状态为TIDYING workerCount值设置为0 失败 自旋重试
        }
    }


    /*
     * 控制worker线程中断的方法。
     *
     */

    /** 翻译：
     * 如果存在安全管理器，请确保调用者通常具有关闭线程的权限(参见shutdownPerm)。
     * 如果它通过，另外确保调用者被允许中断每个工作线程。
     * 如果SecurityManager专门处理某些线程，即使第一次检查通过，这也可能不是真的。
     */

    /**
     * 如果存在安全管理器，请确保调用者通常具有关闭线程的权限
     * 如果存在安全管理器 还要确保调用者允许中断每一个worker线程。
     */
    private void checkShutdownAccess(){
        //获取安全管理器
        SecurityManager security = System.getSecurityManager();
        //是否存在安全管理器
        if (security != null) {
            //校验调用者是否有关闭线程的权限
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            //加锁
            mainLock.lock();
            try {
                //循环检查每个work thread是否具有访问的权限
                for (Worker w : workers) {
                    //检查是否有权限
                    //如果线程不为空（为null抛出异常）或者线程未中断（这部分要看security.checkAccess的实现）
                    // ，同时线程组是RootGroup。则判断线程是否有修改权限，没有权限会抛出SecurityException
                    // 如果不是一个线程组，直接返回
                    security.checkAccess(w.thread);
                }
            }finally {
                //释放锁
                mainLock.unlock();
            }
        }
    }

    /**
     * 中断所有线程，即使是活动的。忽略securityexception(在这种情况下，一些线程可能保持 不被中断)。
     */
    private void interruptWorkers(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            //中断workers容器中执行任务的worker，
            for (Worker w : workers) {
                //当中断worker发生SecurityException时，worker会保持原状态。
                w.interruptIfStarted();
            }
        }finally {
            //解锁
            mainLock.unlock();
        }
    }

    /**
     * 翻译：
     * 中断可能正在等待任务的线程(表示没有被锁定)，以便它们可以检查终止或配置更改。
     * 忽略SecurityException(在这种情况下，一些线程可能保持不间断)。
     *
     * @param onlyOne 如果为true，最多中断一个worker。只有在启用了终止时才从tryTerminate调用。
     *                在这种情况下，在所有线程当前都在等待的情况下，
     *                最多中断一个等待的worker以传播关机信号。
     *                中断任意线程可以确保在关闭开始后新来的工人最终也会退出。
     *                为了保证最终的终止，始终只中断一个空闲的worker就足够了，
     *                但是shutdown()会中断所有空闲的worker，这样冗余的worker就会立即退出，
     *                而不会等待一个掉队的任务完成。
     */

    /**
     *  理解：
     *      中断等待任务的线程。
     * @param onlyOne 1、当值为true时，表示随机中断一个任务线程。但是中断的时候发生SecurityException异常
     *            则忽略这个异常，并结束。（也就是说onlyOne=true 时，有可能不会中断一个worker）
     *                2、当值为false时，表示中断workers容器中所有等待任务的工作线程。（跟值为true一样，
     *            发生异常，忽略异常。同样在该方法执行结束，可能还存在闲置的worker线程）
     */
    private void interruptIdleWorkers(boolean onlyOne){
        final ReentrantLock mainLock = this.mainLock;
        //加锁
        mainLock.lock();
        try {
            //循环遍历所有的workers
            for (Worker w : workers) {
                //获取当前worker的线程
               Thread t = w.thread;
               //判断线程没有被中断 并且 worker 获取锁对象
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        //线程中断
                        t.interrupt();

                    }catch (SecurityException ignore){
                        //忽略异常，如果出现异常，进行下次循环。
                        //这里说明了SecurityException 异常出现了，worker 可能会保持不中断
                    }finally {
                        //释放锁
                        w.unlock();
                    }

                }
                //判断是否中断一个
                if (onlyOne){
                    //是，中断一个退出循环
                    break;
                }
            }
        }finally {
            //释放锁
            mainLock.unlock();
        }
    }

    /**
     * 中断所有的闲置worker线程。
     *
     */
    private void interruptIdleWorkers(){

        interruptIdleWorkers(false);
    }

    /**
     * 默认是中断一个
     */
    private static final boolean ONLY_ONE = true;

    /*
     *  Misc实用程序，其中大多数也被导出到ScheduledThreadPoolExecutor
     *
     */

    /**
     * 为给定命令调用被拒绝的执行处理程序。
     * 由ScheduledThreadPoolExecutor使用的包保护。
     * @param command
     */

    /**
     * 拒绝任务提交的策略。
     * @param command 任务
     */
    final void reject(Runnable command){
        handler.rejectedExecution(command,this);
    }

    /**
     * 在调用shutdown时执行运行状态转换之后执行进一步清理。
     * 这里没有做任何操作，但是由ScheduledThreadPoolExecutor用于取消延迟的任务。
     *
     * 该方法受包保护，在并发包中实现的线程池中只有ScheduledThreadPoolExecutor 重写了该方法。
     * 因为执行shutdown后，ScheduledThreadPoolExecutor 中会有一些未到时执行的任务。
     * 这里需要清除队列中等待执行的任务。
     */
    void onShutdown(){

    }

    /**
     * ScheduledThreadPoolExecutor需要的状态检查，
     * 在关机期间启用运行任务。
     * @param shutdownOK  true  如果 rs == SHUTDOWN，则返回true
     *                    false  如果 rs == RUNNING  ，则返回true
     * @return
     */
    final boolean isRunningOrShutdown(boolean shutdownOK){
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     *  将任务队列排空到一个新的列表中，通常使用drainTo。
     *  但是，如果队列是DelayQueue或轮询或析链可能无法删除某些元素的其他类型队列，
     *  则逐个删除它们。
     * @return
     */
    private List<Runnable> drainQueue(){
        //workQueue
        BlockingQueue<Runnable> q = workQueue;
        //创建一个集合容器
        ArrayList<Runnable> taskList = new ArrayList<>();
        //将队列中的任务转移的taskList中。并清空自身
        q.drainTo(taskList);
        //如果队列是DelayQueue或轮询或析链可能无法删除某些元素的其他类型队列，
        if (!q.isEmpty()) {
            //循环一个一个的取出，逐个删除它们。
            for (Runnable r : q.toArray(new Runnable[0])) {
                //删除队列中对应的任务
                if (q.remove(r)) {
                    //将删除的任务放在taskList中
                    taskList.add(r);
                }

            }
        }
        //返回队列中的任务列表
        return taskList;
    }

    /*
     * 创建、运行和清理workers的方法
     *
     */


    /**
     * 检查是否可以根据当前池状态和给定的边界(核心或最大值)添加新的worker。
     * 如果是，则相应地调整workerCount，并且，如果可能，将创建并启动一个新的worker，
     * 并将firstTask作为它的第一个任务运行。如果池已停止或符合关闭条件，则此方法返回false。
     * 如果线程工厂在请求时未能创建线程，它还返回false。如果线程创建失败，
     * 或者是由于线程工厂返回null，或者是由于异常(通常是thread .start()中的OutOfMemoryError错误)，
     * 我们将干净地回滚。
     * @param firstTask 新线程首先运行的任务(如果没有，则为空)。
     *                  当corePoolSize线程少于一个时(在方法execute()中)，
     *                  或者当队列已满时(在这种情况下，我们必须绕过队列)，
     *                  工人会用一个初始的第一个任务创建(在方法execute()中)，
     *                  以绕过队列。最初，空闲线程通常是通过prestartCoreThread创建的，
     *                  或者用来替换其他垂死的工作线程。
     * @param core 如果为true，则使用corePoolSize作为绑定，否则使用maximumPoolSize。
     *             (这里使用布尔指示器而不是值来确保在检查其他池状态后读取新值)。
     * @return
     */
    private boolean addWorker(Runnable firstTask,boolean core){
        //循环标识
        retry:
        for(;;){
            //获取线程值的状态值
            int c = ctl.get();
            //换算出当前线程所处于的状态
            int rs = runStateOf(c);
            //当线程池状态值大于SHUTDOWN时，不能在提交任务
            //当线程池处于SHUTDOWN时 再次提交新任务会被拒绝
            //当线程池处于SHUTDOWN时 且队列为空，决绝任务提交
            if (rs >= SHUTDOWN &&
                    !(rs == SHUTDOWN  && firstTask == null && !workQueue.isEmpty())) {
                //不能向线程池添加任务
                return false;
            }
            for(;;){
                //获取运行的worker数量
                int wc = workerCountOf(c);
                //当运行的worker数量大于等于线程池最大容量时，拒绝添加
                //或者worker数量小于线程池本身最大线程池容量，大于线程池自定义指定最大线程数时，拒绝添加
                if (wc >= CAPACITY || wc >=(core ? corePoolSize : maximumPoolSize)) {
                    return false;
                }
                //任务添加完成  ctl 中workerCount加1
                if(compareAndIncrementWorkerCount(c)){
                    //结束自旋
                    break retry;
                }
                //失败说明ctl 中workerCount数量发生变化
                //重新读取ctl的值
                c = ctl.get();
                //重新读取的运行状态值于上一次不一致
                if(runStateOf(c) != rs){
                    //重新开始添加，重试外循环
                    continue retry;
                }
                //走到这里说明在上面执行compareAndIncrementWorkerCount(c)方法时，
                // workerCount更改导致CAS失败，在执行 c = ctl.get()时 c的值有变回读取的值;
                // 需要重试内循环

            }
        }

        //执行到这里说明正常添加任务完成了
        //临时标记worker未启动
        boolean workerStarted = false;
        //临时标记worker未添加成功
        boolean workerAdded = false;
        //创建一个空 worker
        Worker w = null;
        try {
            //创建一个新的worker
            w = new Worker(firstTask);
            //通过threadFactory 创建一个新线程
            final Thread t = w.thread;
            //判断threadFactory创建线程是否成功
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                //加主锁
                mainLock.lock();
                try {
                    //换算当前线程池运行的状态
                    int rs = runStateOf(ctl.get());
                    //如果当前线程池变成了SHUTDOWN状态，且这个任务是新提交的，这时需要回滚以上操作。
                    //删除容器中的worker 并将添加的workerCount数值减一

                    //判断线程池是否时运行状态
                    if(rs < SHUTDOWN ||
                            //运行状态为关闭 同时首先任务为null
                            ( rs == SHUTDOWN && firstTask == null )){
                        //走到这里有两种情况，1、线程池处于正常运行状态；2、线程池处于关闭状态，
                        //任务不是初次提交。
                        //检查t是否是存活状态(如果一个线程已经启动并且还没有死，那么它就是活的)
                        //因为创建的线程还没有启动，却已经是运行状态，说明这个线程别篡改，抛出异常
                        if (t.isAlive()) {
                            throw new IllegalThreadStateException();
                        }
                        //将创建的worker添加到works集合中
                        workers.add(w);
                        //获取works 的长度
                        int s = workers.size();
                        //判断worker的长度是否大于最大线程池大小，

                        if (s > largestPoolSize) {
                            //如果是，修改largestPoolSize 为s
                            //记录线程池达到的最大线程数
                            largestPoolSize = s;
                        }
                        //worker添加完成
                        workerAdded = true;
                    }
                }finally {
                    //释放主锁
                    mainLock.unlock();
                }
                //worker添加完成
                if (workerAdded) {
                    //启动任务
                    t.start();
                    //worker启动成功
                    workerStarted = true;
                }
            }
        }finally {
            //当前线程池变成了SHUTDOWN状态，且这个任务是新提交的，还没有执行
            //或者线程池已停止
            //或者threadFactory 创建线程失败。返回为null
            //或者throw new IllegalThreadStateException();
            //或者任务启动失败

            if (!workerStarted){
                //回滚
                //调用addWorkerFailed 删除workers容器中对应的worker
                addWorkerFailed(w);
            }
        }
        //返回worker启动的状态
        return workerStarted;

    }

    /**
     * 回滚工作线程的创建。
     *  -从工人中移除工人(如果存在)
     *  -减少工人计数
     *  -重新检查终止，以防这个工人的存在导致终止
     *
     *
     * @param w
     */
    private void addWorkerFailed(Worker w){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            //如果worker是在添加的workers容器中之后失败的
            if (w != null) {
                //移除对应的worker
                workers.remove(w);
            }
            //workerCount 自减1
            decrementWorkerCount();
            tryTerminate();
        }finally {
            mainLock.unlock();
        }
    }


    /**
     * 为垂死的工人做清洁和记账工作。该方法只在runWorker方法里调用
     * 除非突然完成设置，假设workerCount已经调整到考虑退出。此方法从工作线程集中删除线程，
     * 如果由于用户任务异常退出工作线程，或者运行的工作线程少于corePoolSize，
     * 或者队列非空但没有工作线程，则可能终止线程池或替换该工作线程。
     * @param w
     * @param completedAbruptly worker是否异常结束的，true：是
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly){
        //worker 是否异常结束
        if (completedAbruptly) {
            //worker异常结束，workerCount 减1
            decrementWorkerCount();
        }
        final ReentrantLock mainLock = this.mainLock;
        //加锁
        mainLock.lock();
        try {
            //计算完成的任务数 当worker退出时
            completedTaskCount += w.completedTasks;
            //将该worker从容器中移除
            workers.remove(w);
        }finally {
            //释放锁
            mainLock.unlock();
        }
        //如果workerCount != 0 会尝试中断一个worker 否则会终止线程池
        tryTerminate();
        int c = ctl.get();
        //判断线程池是否处于停止状态
        if (runStateLessThan(c,STOP)) {
            //走到这里。说明线程池处于正常运行状态 或者 处于关闭状态，但是队列中有任务
            //判断任务是不是异常退出的
            if (!completedAbruptly) {
                //根据是否允许核心线程超时获取任务，来计算最小运行的线程
                //这里allowCoreThreadTimeOut =true时，采取最坏的打算。核心线程都被回收了
               int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
               //如果核心线程都被回收了，但是队列里有了任务。
                if (min == 0 && !workQueue.isEmpty()) {
                    //这时就需要创建一个线程。所以最小线程数要求为1
                    min = 1;
                }
                //如果当前队列中有任务，且运行的worker线程数大于等于最少核心线程数时，
                if (workerCountOf(c) >= min) {
                    //不需要处理
                    return;
                }
                //否则。说明当前队列有任务，但是运行的worker线程小于核心线程数，
                // 说明刚刚移除了一个核心线，当前运行的worker数量小于需要的最小线程数
                // 需要在创建一个worker线程来加入处理队列中的任务
                addWorker(null,false);
            }

        }
    }


    /**
     * 执行阻塞或定时等待任务，取决于当前的配置设置，或 返回null，这个worker必须退出，有以下任何一种:
     *  1。有不止maximumPoolSize工作程序(由于调用setMaximumPoolSize)。
     *  2。池子停止了。
     *  3。池被关闭，队列为空。
     *  4。这个worker超时等待一个任务，超时的worker将被终止
     *  (即{@code allowCoreThreadTimeOut || workerCount > corePoolSize})
     *  在定时等待之前和之后，如果队列不是空的，这个worker就不是池中的最后一个线程。
     *
     * @return task or null    如果工作者必须退出，则为空，在这种情况下workerCount将递减
     */
    private Runnable getTask(){

        boolean timeOut = false;
        //自旋
        for(;;){
            //获取运行的worker数量
            int c = ctl.get();
            //根据worker数量换算当前的状态
            int rs = runStateOf(c);
           //如果当前运行的worker线程大于线程池容量所允许的最大线程数，该线程就不能获取到任务，
            // 销毁线程。恢复允许的最大线程数。在开始提供
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                //自减workerCount值
                decrementWorkerCount();
                //返回null
                return null;
            }
            //线程池正常运行，获取工作的worker数量
            int wc = workerCountOf(c);
            //允许核心线程超时回收 或者 运行的workers 数量大于核心线程
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;
            //注意：一个创建成功的线程池 corePoolSize >=0 ,maximumPoolSize >=1 timeOut = false;

            //1、如果workerCount大于最大线程数，不能获取任务，需要回收超过核心线程数的线程
            //2 在核心线程不允许超时获取任务时，当wc <= corePoolSize 且 corePoolSize > 1 ,不能获取任务，
            // 说明提交的任务都被核心线程执行了，在运行的线程数超过核心线程数时，会入队列，反之不会入队列
            // 超过核心线程的线程会向队列中获取任务执行
            //3、如果workerCount<=时，且 队列为空时，不会向队列中获取任务。
            //以上情况满足一个，
            // 就会执行workerCount cas 自减操作，执行成功返回null 说明不能获取任务；执行失败，从头再来
            if ((wc > maximumPoolSize || (timed && timeOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                //workerCount CAS方式自减1
                if(compareAndDecrementWorkerCount(c)){
                    //成功返回空任务
                    return null;
                }
                //如果workerCount自减失败，从头在来
                continue;
            }

            try {
                //默认情况下，当运行的workers 数量大于核心线程，会超时获取队列中的任务，反之阻塞获取
                //当设置核心线程有超时机制，会超时获取队列中的任务，反之阻塞获取
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime,TimeUnit.NANOSECONDS) : workQueue.take();
               //如果获取到了任务，就返回。
                if (r != null) {
                    return r;
                }
            }catch (InterruptedException retry){
                //中断异常，捕获，重试
                timeOut = false;
            }

        }

    }

    /**
     *  主工作程序运行循环。从队列中反复获取任务并执行它们，同时处理一些问题:
     *   1. 我们可以从一个初始任务开始，在这种情况下，我们不需要获得第一个任务。
     *      否则，只要池在运行，我们就会从getTask获得任务。如果返回null，
     *      则工作程序将由于池状态或配置参数的更改而退出。其他退出是由于抛出外部代码中的异常导致的，
     *      在这种情况下 completedAbruptly 保持，这通常导致processWorkerExit替换此线程。
     *
     *   2. 在运行任何任务之前，会获得锁，以防止任务执行时其他池中断，然后我们确保除非池停止，
     *      否则这个线程不会设置它的中断。
     *
     *   3. 在每个任务运行之前，都会调用beforeExecute，这可能会抛出一个异常，在这种情况下，
     *      我们会导致线程在不处理任务的情况下死亡(用completedsuddenly true中断循环)。
     *
     *   4. 假设beforeExecute正常完成，我们运行任务，收集它抛出的任何异常发送到afterExecute。
     *      我们分别处理RuntimeException、Error(规范保证会捕获这两者)和任意可抛掷。
     *      因为我们不能在Runnable.run中重新抛出可抛弃物，所以我们在抛出时将它们包装在错误中
     *      (到线程的UncaughtExceptionHandler中)。保守地说，抛出的任何异常都会导致线程死亡。
     *
     *
     *   5. 在task.run完成后，我们调用afterExecute，它也可能抛出一个异常，这也将导致线程死亡。
     *      根据 JLS Sec 14.20，即使task.run抛出，这个异常也会生效。
     *
     *      异常机制的最终效果是，在执行后，线程的UncaughtExceptionHandler会像我们提供的那样，
     *      提供关于用户代码遇到的任何问题的准确信息。
     *
     * @param w 一个worker
     */
    final  void runWorker(Worker w){
        //获取当前线程
        Thread wt = Thread.currentThread();
        //获取w中的首个任务
        Runnable task = w.firstTask;
        //将w 中首个任务置空
        w.firstTask = null;
        //允许中断 放弃锁的占有权
        w.unlock();
        //标识是否异常中断
        boolean completedAbruptly = true;
        try {
            //如果当前worker中的任务为空，就从队列中获取一个任务
            while (task != null || (task = getTask()) != null){
                //获取锁，获取不到入队列阻塞
                w.lock();
                //如果池停止，确保线程被中断;
                //如果没有，确保线程没有被中断。
                //这需要在第二种情况下重新检查，以处理在清除中断时 shutdownNow

                //如果池没有停止，确保当前线程没有被中断
                //如果线程停止，确保中断线程
                if ((runStateLessThan(ctl.get(),STOP)||
                        (Thread.interrupted() && runStateAtLeast(ctl.get(),STOP))) &&
                        !wt.isInterrupted()) {

                    //线程中断
                    wt.interrupt();
                }
                try {
                    //运行前的准备工作，ThreadPoolExecutor没有实现，该方法是protected修饰
                    //说明如果在执行任务前需要做某些准备时，可以在子类中定制。
                    beforeExecute(wt,task);
                    //异常对象
                    Throwable thrown = null;
                    try {
                        //执行线程的run方法
                        task.run();
                    }catch (RuntimeException x){
                        //如果发生运行异常记录并抛出。
                        thrown = x; throw x;
                    }catch (Error x){
                        //如果发生Error 异常记录并抛出
                        thrown = x; throw x;
                    }catch (Throwable x){
                        //如果发生的异常既不是RuntimeException，也不是Error，
                        // 记录异常，并将异常转换为Error异常抛出
                        thrown = x; throw new Error(x);
                    }finally {
                        //最后执行执行后的方法，
                        // 可以对任务执行完成的结果，或者执行过程中发生的异常进行操作
                        afterExecute(task,thrown);
                    }
                }finally {
                    task = null;
                    //不管是正常执行完成结束，还是异常结束，完成任务数都会加1
                    w.completedTasks++;
                    //释放锁资源
                    w.unlock();
                }
            }
            //走到这里说明任务是正常执行结束的
            completedAbruptly = false;
        }finally {
            //执行到这里completedAbruptly有两种情况：
            // 1、true：说明任务在执行的过程中发生了异常
            // 2、false：说明任务是正常结束
            //对worker完成任务做一些处理
            processWorkerExit(w,completedAbruptly);
        }


    }


    //公共构造函数和方法

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize,long keepAliveTime,
                              TimeUnit unit, BlockingQueue<Runnable> workQueue){
        this(corePoolSize,maximumPoolSize,keepAliveTime,unit,workQueue,
                Executors.defaultThreadFactory(),defaultHandler);

    }


    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize,long keepAliveTime,
                              TimeUnit unit,BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory){
        this(corePoolSize,maximumPoolSize,keepAliveTime,unit,workQueue,
                threadFactory,defaultHandler);
    }



    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize,long keepAliveTime,
                              TimeUnit unit,BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler){
        this(corePoolSize,maximumPoolSize,keepAliveTime,unit,workQueue,
                Executors.defaultThreadFactory(),handler);
    }


    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize, long keepAliveTime,
                              TimeUnit unit,BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,RejectedExecutionHandler handler){
        if(corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime< 0){
            throw new IllegalArgumentException();
        }

        if (workQueue == null || threadFactory == null || handler == null) {
            throw new NullPointerException();
        }
        this.acc = System.getSecurityManager() == null ? null : AccessController.getContext();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }


    @Override
    public void execute(Runnable command){
        if (command == null) {
            throw new NullPointerException();
        }

        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command,true)) {
                return;
            }
            c = ctl.get();
        }

        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            if (!isRunning(recheck) && remove(command)) {
                reject(command);
            }else if (workerCountOf(recheck) == 0){
                addWorker(null,false);
            }
        }else if (!addWorker(command,false)){
            reject(command);
        }
    }



    @Override
    public void shutdown(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown();
        }finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    @Override
    public List<Runnable> shutdownNow(){
        List<Runnable> tasks ;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        }finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    @Override
    public boolean isShutdown(){
        return ! isRunning(ctl.get());
    }


    public boolean isTerminating(){
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c,TERMINATED);
    }

    @Override
    public boolean isTerminated(){
        return runStateAtLeast(ctl.get(),TERMINATED);
    }


    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        try {
            for (;;){
                if (runStateAtLeast(ctl.get(),TERMINATED)) {
                    return true;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = termination.awaitNanos(nanos);
            }
        }finally {
            mainLock.unlock();
        }
    }



    @Override
    protected void finalize(){
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        }

    }


    public void setThreadFactory(ThreadFactory threadFactory){
        if (threadFactory == null) {
            throw new NullPointerException();
        }
        this.threadFactory = threadFactory;
    }




    public ThreadFactory getThreadFactory(){
        return threadFactory;
    }


    public void setRejectedExecutionHandler(RejectedExecutionHandler handler){
        if (handler == null) {
            throw new NullPointerException();
        }
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler(){
        return handler;
    }

    public void setCorePoolSize(int corePoolSize){
        if(corePoolSize < 0){
            throw new IllegalArgumentException();
        }
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize) {
            interruptIdleWorkers();
        }else if(delta > 0) {

            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty()) {
                    break;
                }
            }
        }

    }

    public int getCorePoolSize(){
        return corePoolSize;
    }

    /**
     * 启动一个核心线程，使其无所事事地等待工作。这将覆盖只有在执行新任务时才启动核心线程的默认策略。
     * 如果所有核心线程都已经启动，这个方法将返回{@code false}。
     * @return
     */
    public boolean prestartCoreThread(){
        return workerCountOf(ctl.get()) < corePoolSize &&
                addWorker(null,true);
    }

    /**
     * 与prestartCoreThread相同，只是在corePoolSize为0时至少启动一个线程。
     */
    void ensurePrestart(){
        int wc = workerCountOf(ctl.get());
        if(wc < corePoolSize){
            addWorker(null,true);
        }else if(wc == 0){
            addWorker(null,false);
        }
    }

    /**
     * 启动所有核心线程，使它们空闲地等待工作。
     * 这将覆盖只有在执行新任务时才启动核心线程的默认策略。
     * @return
     */
    public int prestartAllCoreThreads(){
        int n = 0;
        while (addWorker(null,true)){
            ++n;
        }
        return n;
    }


    public boolean allowsCoreThreadTimeOut(){
        return allowCoreThreadTimeOut;
    }


    public void allowCoreThreadTimeOut(boolean value){
        if(value && keepAliveTime <= 0){
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        if (value != allowCoreThreadTimeOut){
            allowCoreThreadTimeOut = value;
            if (value){
                interruptIdleWorkers();
            }
        }
    }

    public void setMaximumPoolSize(int maximumPoolSize){
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException();
        }
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize) {
            interruptIdleWorkers();
        }
    }

    public int getMaximumPoolSize(){
        return maximumPoolSize;
    }

    public void setKeepAliveTime(long time, TimeUnit unit){
        if(time < 0){
            throw new IllegalArgumentException();
        }
        if (time == 0 && allowsCoreThreadTimeOut()){
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }

        long keepAliveTime = unit.toNanos(time);
        long delta =  keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0){
            interruptIdleWorkers();
        }
    }

    public long getKeepAliveTime(TimeUnit unit){

        return unit.convert(keepAliveTime,TimeUnit.NANOSECONDS);
    }

    /*
     * 用户级队列工具
     */



    public BlockingQueue<Runnable> getQueue(){
        return workQueue;
    }


    public boolean remove(Runnable task){
        boolean removed = workQueue.remove(task);
        tryTerminate();
        return removed;
    }



    public void purge(){
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()){
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled()) {
                    it.remove();
                }
            }
        }catch (ConcurrentModificationException fallThrough){
            for (Object r : q.toArray()) {
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled()) {
                    q.remove(r);
                }
            }
        }
        tryTerminate();
    }


    /*
     * 统计数据
     */

    /**
     * 返回池中的当前线程数。
     * @return
     */
    public int getPoolSize(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            //删除isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(),TIDYING) ? 0 : workers.size();
        }finally {
            mainLock.unlock();
        }
    }

    public int getActiveCount(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers) {
                if (w.isLocked()) {
                    ++n;
                }
            }
            return n;
        }finally {
            mainLock.unlock();
        }
    }

    /**
     * 返回池中曾经同时存在的最大数量的线程。
     * @return
     */
    public int getLargestPoolSize(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        }finally {
            mainLock.unlock();
        }
    }

    /**
     * 返回曾经计划执行的任务的大致总数。由于任务和线程的状态在计算期间可能会动态变化，
     * 所以返回值只是一个近似值。
     * @return
     */
    public long getTaskCount(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked()) {
                    ++n;
                }
            }
            return n + workQueue.size();
        }finally {
            mainLock.unlock();
        }
    }

    public long getCompletedTaskCount(){
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
             long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
            }
            return n;
        }finally {
            mainLock.unlock();
        }
    }


    @Override
    public String toString(){
        long ncompleted;
        int nworkers , nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked()) {
                    ++nactive;
                }
            }
        }finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c,SHUTDOWN) ? "Running" :
                (runStateAtLeast(c,TERMINATED)? "Terminated" : "Shutting down"));

        return super.toString() + "[" + rs+ ", pool size = " + nworkers + ", active threads = " +
                nworkers + ", queued tasks = " + workers.size() + ", completed tasks = " +
                ncompleted + "]";
    }


    /*
     * 扩展钩子
     */

    /**
     * 在给定线程中执行给定可运行程序之前调用的方法。
     * 此方法由线程{@code t}调用，它将执行task {@code r}，并可用于重新初始化线程局部变量或执行日志记录。
     *
     *  这个实现什么都不做，但是可以在子类中定制。
     *  注意:为了正确嵌套多个覆盖，子类通常应该调用{@code super.beforeExecute}在这个方法的末尾。
     * @param t 将运行task的线程{@code r}
     * @param r 将要执行的任务
     */
    protected void beforeExecute(Thread t, Runnable r){}

    /**
     * 在给定的可运行程序执行完成时调用的方法。此方法由执行任务的线程调用。
     * 如果 t 非空，是异常导致执行突然终止的{@code RuntimeException}或{@code Error}。
     *
     * 这个实现什么都不做，但是可以在子类中定制。
     * 注意:为了正确嵌套多个覆盖，子类通常应该调用{@code super.afterExecute},在方法的开始地方。
     *
     *
     * 注意:当动作被显式或通过{@code submit}等方法包含在任务中(如{@link FutureTask})时，
     * 这些任务对象捕获并维护计算异常，因此它们不会导致突然终止，内部异常<em>not</em>传递给该方法。
     * 如果你想在这个方法中捕获这两种失败，你可以进一步探测这种情况，比如在这个示例子类中，如果一个任务被中止，
     * 它会打印直接原因或底层异常:
     * //例子：
     *<pre> {@code
     *       class ExtendedExecutor extends ThreadPoolExecutor {
     *         // ...
     *         protected void afterExecute(Runnable r, Throwable t) {
     *           super.afterExecute(r, t);
     *           if (t == null && r instanceof Future<?>) {
     *             try {
     *             //如果是Future的字类，则阻塞等待线程执行并获取结果
     *               Object result = ((Future<?>) r).get();
     *             } catch (CancellationException ce) {
     *                 t = ce;
     *             } catch (ExecutionException ee) {
     *                 t = ee.getCause();
     *             } catch (InterruptedException ie) {
     *                  //如果中断，忽略该异常，并恢复线程继续执行
     *                 Thread.currentThread().interrupt();
     *             }
     *           }
     *           if (t != null)
     *           //如果有异常打印异常
     *             System.out.println(t);
     *         }
     *       }}</pre>
     *
     *
     * 这个方法的开始。
     * @param r
     * @param t
     */
    protected void afterExecute(Runnable r, Throwable t){

    }

    protected void terminated() { }

    public static class CallerRunsPolicy implements RejectedExecutionHandler{

        public CallerRunsPolicy(){}

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }



    public static class AbortPolicy implements RejectedExecutionHandler{

        public AbortPolicy(){}

        /**
         * 总是抛出RejectedExecutionException
         * @param r
         * @param e
         */
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    "rejected form "+ e.toString());
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler{

        public DiscardPolicy(){}

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {

        }
    }


    public static class DiscardOldestPolicy implements RejectedExecutionHandler{

        public DiscardOldestPolicy(){}

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }





}
