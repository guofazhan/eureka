package com.netflix.eureka.util.batcher;

import java.util.List;

/**
 * 任务执行接口
 * An interface to be implemented by clients for task execution.
 *
 * @author Tomasz Bak
 */
public interface TaskProcessor<T> {

    /**
     * A processed task/task list ends up in one of the following states:
     * <ul>
     *     <li>{@code Success} processing finished successfully</li>
     *     <li>{@code TransientError} processing failed, but shall be retried later</li>
     *     <li>{@code PermanentError} processing failed, and is non recoverable</li>
     * </ul>
     */
    enum ProcessingResult {
        //处理成功
        Success,
        Congestion,
        //处理失败
        TransientError,
        //处理失败且无法恢复
        PermanentError
    }

    /**
     * 执行单个任务并返回执行结果
     * In non-batched mode a single task is processed at a time.
     */
    ProcessingResult process(T task);

    /**
     * 执行一批任务，并返回执行结果
     * For batched mode a collection of tasks is run at a time. The result is provided for the aggregated result,
     * and all tasks are handled in the same way according to what is returned (for example are rescheduled, if the
     * error is transient).
     */
    ProcessingResult process(List<T> tasks);
}
