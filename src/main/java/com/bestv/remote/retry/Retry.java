package com.bestv.remote.retry;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 异步重试
 *
 * @author taojiacheng
 */
@Slf4j
public class Retry<T> {


    /**
     * 队列最大容量
     * <p>
     * 估算: 一个任务预估 50 字节, 1g 堆内存理论允许的 最大队列容量为约为  2^32 / 50 约 2 千万
     * 默认设置 Integer.MAX_VALUE / 1000 约 2 百万
     * <p>
     * 必须大于 THRESHOLD
     */
    protected final static int QUEUE_MAX_SIZE = Integer.MAX_VALUE / 1000;
    /**
     * 重试任务队列
     */
    private final static Queue<RetryTask<?>> RETRY_TASKS_QUEUE = new TimeBaseLinkedQueue<>();
    /**
     * 监视锁
     */
    private static final Object SYNC_MONITOR = new Object();


//===========================队列缓冲区====================================
    /**
     * 重试执行次数
     */
    protected int retryTimes;

//===========================消费者====================================
    /**
     * 重试时间间隔 单位：秒
     */
    protected int[] retryIntervals;


//===========================生产者====================================
    /**
     * 重试方法
     */
    protected RetryFunction<T> retryFunction;
    /**
     * 重试断言
     */
    protected RetryPredicate<T, Throwable> again;
    /**
     * 重试结束的回调
     */
    private RetryCallback<RetryTask<T>, Throwable> whenFinished;
    /**
     * 重试次数用尽后的回调
     */
    private Consumer<RetryTask<T>> whenExceed;
    /**
     * 当任务队列已满，任务被丢弃时
     */
    private Consumer<RetryTask<T>> whenAbandon;
    /**
     * 执行任务后回调
     */
    private RetryCallback<RetryTask<T>, Throwable> whenExecute;

    public Retry() {
        // 单例启动消费者
        RetryConsumer.singletonInstance();
    }

    public Retry<T> retryIntervals(int... retryIntervals) {
        for (int retryInterval : retryIntervals) {
            if (retryInterval < 1) {
                throw new IllegalArgumentException("重试间隔不得低于1秒！");
            }
        }
        this.retryIntervals = retryIntervals;
        return this;
    }

    public Retry<T> retryTimes(int retryTimes) {
        if (retryTimes < 1) {
            throw new IllegalArgumentException("重试次数至少为1次！");
        }
        this.retryTimes = retryTimes;
        return this;
    }

    /**
     * 重试方法
     */
    public Retry<T> retryFunction(RetryFunction<T> function) {
        this.retryFunction = function;
        return this;
    }

    /**
     * 重试断言
     */
    public Retry<T> again(RetryPredicate<T, Throwable> again) {
        this.again = again;
        return this;
    }

    /**
     * 执行回调
     */
    public Retry<T> whenExecute(RetryCallback<RetryTask<T>, Throwable> whenExecute) {
        this.whenExecute = whenExecute;
        return this;
    }

    /**
     * 重试结束的回调
     */
    public Retry<T> whenFinished(RetryCallback<RetryTask<T>, Throwable> whenFinished) {
        this.whenFinished = whenFinished;
        return this;
    }

    /**
     * 重试次数用尽回调
     */
    public Retry<T> whenExceed(Consumer<RetryTask<T>> whenExceed) {
        this.whenExceed = whenExceed;
        return this;
    }

    /**
     * 当任务队列已满，任务被丢弃时
     */
    public Retry<T> whenAbandon(Consumer<RetryTask<T>> whenAbandon) {
        this.whenAbandon = whenAbandon;
        return this;
    }

    /**
     * 只有第一次执行成功，才会有返回值
     * 如果发生重试，只能返回 null
     * 后续重试成功，返回值可以在回调通过 retryTask.getResult() 获取
     */
    public T execute() {
        checkForExecute();
        RetryTask<T> retryTask = new RetryTask<>(retryTimes, retryIntervals, retryFunction, again,
                whenExecute, whenFinished, whenExceed, whenAbandon);
        // 首次执行是同步的
        boolean needRetry = retryTask.execute();
        if (needRetry) {
            RETRY_TASKS_QUEUE.add(retryTask);
            return null;
        } else {
            // 只有第一次执行成功了，不用再重试，才返回结果
            return retryTask.result;
        }
    }

    /**
     * 首次执行前的参数校验
     */
    private void checkForExecute() {
        assert retryFunction != null;
        assert again != null;
        assert retryTimes > 0;
        assert retryIntervals.length == retryTimes;
    }

    /**
     * 用来接收要处理的任务
     */
    @FunctionalInterface
    public interface RetryFunction<T> {

        /**
         * 待处理的任务
         *
         * @return 重试方法的执行结果
         * @throws Throwable 重试时发生的异常
         */
        T apply() throws Throwable;
    }


    /**
     * 重试时的回调通知
     *
     * @param <T> retryTask
     * @param <V> 执行时异常
     */
    @FunctionalInterface
    public interface RetryCallback<T, V extends Throwable> {

        /**
         * 重试结束后的通知
         *
         * @param retryTask 任务
         * @param e         异常
         */
        void callback(final T retryTask, final V e);

    }


    @FunctionalInterface
    public interface RetryPredicate<T, V extends Throwable> {

        /**
         * 重试断言
         *
         * @param retryTask 任务
         * @param e         异常
         * @return 是否需要重试
         */
        boolean test(final T retryTask, final V e);

    }

    /**
     * todo 使用时间轮算法或者小顶堆优化
     * <p>
     * 复写 add 方法，保持按时间排序
     */
    static class TimeBaseLinkedQueue<E extends RetryTask<?>> extends LinkedList<E> {

        /**
         * 队首弹出元素
         */
        @SneakyThrows
        @Override
        public E poll() {
            synchronized (SYNC_MONITOR) {
                // 阻塞消费者
                while (isEmpty()) {
                    log.debug("队列为空，消费者进入等待，消费者释放对象锁，等待产生任务...");
                    SYNC_MONITOR.wait();
                    log.debug("消费者被唤醒，执行消费任务... 当前队列容量 : {}", size());
                }
                E retryTask = super.pollFirst();
                while (!retryTask.canStart()) {
                    long waitTime = retryTask.waitTime();
                    log.debug("任务 [{}] 未到达执行时间，等待 {} ms，释放对象锁...", retryTask.getTaskName(), waitTime);
                    SYNC_MONITOR.wait(waitTime);
                    log.debug("任务 [{}] 等待结束，执行消费任务... 当前队列容量 : {}", retryTask.getTaskName(), size());
                }
                SYNC_MONITOR.notifyAll();
                log.debug("弹出任务 [{}] ，当前队列容量 : {}，唤醒生产者...", retryTask.getTaskName(), size());
                return retryTask;
            }
        }

        /**
         * 向队列添加元素
         * 重写保证最先执行的任务在队首
         */
        @SneakyThrows
        @Override
        public boolean add(E e) {
            synchronized (SYNC_MONITOR) {
                while (size() >= QUEUE_MAX_SIZE) {
                    log.debug("队列已满，等待消费者消费，释放对象锁...");
                    SYNC_MONITOR.wait();
                    log.debug("生产者被唤醒，产生或放回任务 [{}]... 当前队列容量 : {}", e.getTaskName(), size());
                }
                boolean flag = false;
                if (isEmpty()) {
                    flag = super.add(e);
                }
                // 保证最先执行的任务在队首
                for (int i = 0; i < size(); i++) {
                    if (e.getNextExecuteTime().getTime() < get(i).getNextExecuteTime().getTime()) {
                        super.add(i, e);
                        flag = true;
                        break;
                    }
                }
                if (!flag) {
                    flag = super.add(e);
                }
                SYNC_MONITOR.notifyAll();
                log.debug("添加任务 [{}]，当前队列容量 : {}，唤醒消费者", e.getTaskName(), size());
                return flag;
            }
        }

    }

    static class RetryConsumer implements Runnable {

        /**
         * 内部消费队列
         * 临时保存重放任务
         */
        protected static final Queue<RetryTask<?>> CONSUMER_QUEUE = new ConcurrentLinkedQueue<>();
        /**
         * cpu 核心数
         */
        private final static int PROCESSORS_NUMS = Runtime.getRuntime().availableProcessors();
        /**
         * 消费者线程数
         */
        private final static int CONSUMER_THREAD_SIZE = PROCESSORS_NUMS > 2 ? PROCESSORS_NUMS / 2 : 1;
        /**
         * 消费者内部重放队列的阈值
         */
        private final static int THRESHOLD = CONSUMER_THREAD_SIZE * 10 + 1;
        /**
         * 消费者单例实例
         */
        private static volatile RetryConsumer INSTANCE = null;

        static {
            // 执行消费任务
            for (int i = 0; i < CONSUMER_THREAD_SIZE; i++) {
                // 监听任务队列，线程不会进入终止状态，不适合使用线程池
                Thread thread = new Thread(singletonInstance());
                thread.setName("retry-consumer-" + i);
                thread.setDaemon(true);
                thread.start();
            }
        }

        /**
         * 保证单个实例
         */
        private RetryConsumer() {
        }

        /**
         * 启动消费者
         * 保证单例
         */
        public static RetryConsumer singletonInstance() {
            if (INSTANCE != null) {
                return INSTANCE;
            }
            synchronized (RetryConsumer.class) {
                if (INSTANCE != null) {
                    return INSTANCE;
                }
                INSTANCE = new RetryConsumer();
                return INSTANCE;
            }
        }


        /**
         * 队列满了的时候，消费者取出一个任务，队列 有 1 容量
         * 此时生产者生产了2个任务，导致队列满，生产者阻塞等待
         * 此时消费者消费完毕，任务需要重试，重新放入队列，而队列已经是满状态，消费者阻塞
         * 导致生产者和消费者同时阻塞
         * <p>
         * 解决方案: 双队列
         */
        @Override
        public void run() {
            for (; ; ) {
                /**
                 * 优先消费内部队列中的任务
                 * 设置 -THRESHOLD 的目的就是为了 1. 消费者不要把任务队列填充满，导致消费者阻塞
                 */
                while (RETRY_TASKS_QUEUE.size() < QUEUE_MAX_SIZE - THRESHOLD && !CONSUMER_QUEUE.isEmpty()) {
                    RetryTask<?> task = CONSUMER_QUEUE.poll();
                    if (task == null) {
                        // 并发情况下会出现 task == null 的情况
                        continue;
                    }
                    RETRY_TASKS_QUEUE.add(task);
                }

                RetryTask<?> retryTask = RETRY_TASKS_QUEUE.poll();
                if (retryTask == null) {
                    continue;
                }
                if (retryTask.isExceed()) {
                    log.debug("任务执行次数用尽，仍然没有重试成功，移除任务 [{}]，当前队列剩余容量：{}", retryTask.getTaskName(), RETRY_TASKS_QUEUE.size());
                    continue;
                }
                // 执行任务
                boolean needRetry = retryTask.execute();

                if (needRetry) {
                    if (RETRY_TASKS_QUEUE.size() >= QUEUE_MAX_SIZE - THRESHOLD) {
                        /**
                         * 消费者内部维护一个重试队列，当任务满了，消费者将任务放到内部队列中
                         * 重试队列空闲时，优先将内部队列任务取出，放入重试队列
                         * 保证消费者不会 wait，防止 消费者和生产者同时等待无法唤醒
                         */
                        if (CONSUMER_QUEUE.size() > THRESHOLD) {
                            /**
                             * 队列容量已经达到阈值，执行拒绝策略
                             * 为保护系统，防止OOM，丢弃当前任务
                             * 实际上，只有在极端的情况或者任务设置错误的情况下，才会触发此分支，可以忽略
                             */
                            log.warn("重试任务队列已满，拒绝当前任务 [{}]，当前任务数量：{}", retryTask, RETRY_TASKS_QUEUE.size() + THRESHOLD);
                            retryTask.beforeAbandon();
                            continue;
                        }
                        CONSUMER_QUEUE.offer(retryTask);
                        log.debug("消费者放回任务 [{}] 到任务队列，但是任务队列将满，将任务放到空闲队列....", retryTask.getTaskName());
                    } else {
                        RETRY_TASKS_QUEUE.add(retryTask);
                    }
                }
            }
        }
    }


//===========================任务====================================

    @ToString
    public static class RetryTask<T> {

        /**
         * 回调线程池
         */
        private final static ExecutorService CALL_BACK_THREAD_POOL;


        static {
            CALL_BACK_THREAD_POOL = new ForkJoinPool();
        }

        /**
         * 任务标识
         */
        @Getter
        private final String taskName = UUID.randomUUID().toString().substring(0, 8);

        /**
         * 重试时间间隔
         */
        @Getter
        private final int[] retryIntervals;


        /**
         * 重试次数
         */
        @Getter
        private final int retryTimes;

        /**
         * 重试方法
         */
        @Getter
        private final RetryFunction<T> function;

        /**
         * 重试断言
         */
        @Getter
        private final RetryPredicate<T, Throwable> again;


        /**
         * 重试结束的回调
         */
        @Getter
        private final RetryCallback<RetryTask<T>, Throwable> whenFinished;


        /**
         * 执行次数用尽之后的回调
         */
        @Getter
        private final Consumer<RetryTask<T>> whenExceed;


        /**
         * 任务队列已满，任务被丢弃时的回调
         */
        @Getter
        private final Consumer<RetryTask<T>> whenAbandon;


        /**
         * 执行任务后回调
         */
        @Getter
        private final RetryCallback<RetryTask<T>, Throwable> whenExecute;
        /**
         * 当前执行次数
         * 实际上 同一个任务只会被一个线程同时消费，这里不会有线程安全问题
         * 出于 api 的便利，使用 AtomicInteger
         * <p>
         * 为防止业务上修改当前执行次数，造成 nextExecuteTime 计算不准确，对外暴露 currentExecuteTime
         */
        private final AtomicInteger executeTimes;
        /**
         * 当前执行次数
         */
        private final int executes;
        /**
         * 下次执行时间
         */
        @Getter
        private Date nextExecuteTime;
        /**
         * 执行结果
         */
        @Getter
        private T result;


        protected RetryTask(int retryTimes, int[] retryIntervals, RetryFunction<T> function, RetryPredicate<T, Throwable> again,
                            RetryCallback<RetryTask<T>, Throwable> whenExecute, RetryCallback<RetryTask<T>, Throwable> whenFinished,
                            Consumer<RetryTask<T>> whenExceed, Consumer<RetryTask<T>> whenAbandon) {
            this.retryTimes = retryTimes;
            this.retryIntervals = retryIntervals;
            this.function = function;
            this.executeTimes = new AtomicInteger(0);
            this.again = again;
            this.whenExecute = whenExecute;
            this.whenFinished = whenFinished;
            this.whenExceed = whenExceed;
            this.whenAbandon = whenAbandon;
            this.executes = this.executeTimes.get();
        }

        /**
         * 该方法不会有线程安全问题
         *
         * @return 是否需要重试, 不需要重试的任务在完成回调后直接丢弃，不会再触发重试
         */
        protected boolean execute() {
            // flag 是否进行下一轮重试
            boolean reAgain;
            // 捕获到的异常
            Throwable throwable = null;
            // 计算并更新下次执行的时间
            nextExecuteTime = calcNextExecuteTime();
            // 执行业务
            try {
                this.result = function.apply();
            } catch (Throwable e) {
                throwable = e;
            } finally {
                // 重试执行完成, 触发重试执行回调，每次重试都会执行此回调
                log.debug("[{}] 当前任务已经执行了 {} 次，当前执行结果：{}，一共需要重试 {} 次，下次执行时间:{}, 当前队列容量:{}", taskName, executeTimes.get(),
                        result, retryTimes, nextExecuteTime, RETRY_TASKS_QUEUE.size());
                if (whenExecute != null) {
                    final Throwable ft = throwable;
                    CALL_BACK_THREAD_POOL.execute(() -> whenExecute.callback(this, ft));
                }
            }
            reAgain = again.test(result, throwable);
            // 重试结束，不需要在进行下一次的重试，触发重试完成通知
            if (!reAgain && whenFinished != null) {
                final Throwable ft = throwable;
                CALL_BACK_THREAD_POOL.execute(() -> whenFinished.callback(this, ft));
            }
            return reAgain;
        }

        public int getExecutes() {
            return this.executeTimes.get();
        }

        /**
         * 计算下次执行时间，并更新执行次数
         * 更新执行次数和下一次的执行时间
         */
        protected Date calcNextExecuteTime() {
            int interval = 0;
            // 当前执行的次数
            int executeTime = executeTimes.getAndIncrement();
            /**
             * 没有达到最后一次任务的时候，取下一次的间隔时间
             * 达到之后，下次任务间隔时间为 0，确保消费完毕的任务及时弹出并回调
             */
            if (executeTime < retryIntervals.length) {
                interval = retryIntervals[executeTime];
            }
            Calendar calendar = Calendar.getInstance();
            // 日期向后偏移 interval 秒
            calendar.add(Calendar.SECOND, interval);
            return calendar.getTime();
        }


        /**
         * 能否开始任务
         */
        protected boolean canStart() {
            return new Date().after(nextExecuteTime);
        }


        /**
         * 距离任务开始的时间
         */
        protected long waitTime() {
            long waitTime = nextExecuteTime.getTime() - System.currentTimeMillis();
            waitTime = waitTime < 0 ? 0 : waitTime;
            return waitTime + 10;
        }

        /**
         * 任务执行次数是否已经用尽 仍然没有重试成功
         */
        protected boolean isExceed() {
            boolean exceed = executeTimes.get() > retryTimes;
            if (exceed && whenExceed != null) {
                // 重试失败，重试次数已经用尽
                CALL_BACK_THREAD_POOL.execute(() -> whenExceed.accept(this));
            }
            return exceed;
        }

        /**
         * 任务被抛弃回调
         */
        protected void beforeAbandon() {
            if (whenAbandon != null) {
                CALL_BACK_THREAD_POOL.execute(() -> whenAbandon.accept(this));
            }
        }

    }
}