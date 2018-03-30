package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 监管定时任务的任务
 * 主管的任务安排，执行任务超时。
 * 包装的任务必须是线程安全的。
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    /**
     * 超时计数器
     */
    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    /**
     * 定时任务服务 用于定时【发起】子任务。
     */
    private final ScheduledExecutorService scheduler;
    /**
     * 执行子任务线程池 用于【提交】子任务执行。
     */
    private final ThreadPoolExecutor executor;
    /**
     * 子任务执行超时时间，单位：毫秒
     */
    private final long timeoutMillis;
    /**
     * 子任务
     */
    private final Runnable task;

    /**
     * 前子任务执行频率，单位：毫秒。值等于 timeout 参数
     */
    private final AtomicLong delay;
    /**
     *最大子任务执行频率  ，单位：毫秒。值等于 timeout * expBackOffBound 参数
     * 子任务执行超时情况下使用
     */
    private final long maxDelay;

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.scheduler = scheduler;
        //任务执行的线程池
        this.executor = executor;
        //任务执行的超时时长
        this.timeoutMillis = timeUnit.toMillis(timeout);
        //任务实例
        this.task = task;
        //当前任子务执行频率
        this.delay = new AtomicLong(timeoutMillis);
        //最大子任务执行频率  expBackOffBound=执行超时后的延迟重试的时间
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    /**
     *  TimedSupervisorTask 执行时，提交 task 到 executor 执行任务。
     *  当 task 执行正常，TimedSupervisorTask 再次提交自己到scheduler 延迟 timeoutMillis 执行。
     *  当 task 执行超时，重新计算延迟时间( 不允许超过 maxDelay )，再次提交自己到scheduler 延迟执行。
     */
    @Override
    public void run() {
        Future<?> future = null;
        try {
            //任务放入线程池中执行
            future = executor.submit(task);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            //获取任务执行结果，并设置任务超时时间
            // block until done or timeout
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            //设置任务的延迟时长
            delay.set(timeoutMillis);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
        } catch (TimeoutException e) {
            // 子任务 task 执行超时，重新计算下一次执行延迟 delay 。
            // 计算公式为 Math.min(maxDelay, currentDelay * 2) 。
            // 如果多次超时，超时时间不断乘以 2 ，不允许超过最大延迟时间( maxDelay )。
            logger.warn("task supervisor timed out", e);
            //超时计数器+1
            timeoutCounter.increment();

            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            delay.compareAndSet(currentDelay, newDelay);

        } catch (RejectedExecutionException e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }

            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }

            throwableCounter.increment();
        } finally {
            if (future != null) {
                future.cancel(true);
            }

            if (!scheduler.isShutdown()) {
                //调度下一次 TimedSupervisorTask
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }
}