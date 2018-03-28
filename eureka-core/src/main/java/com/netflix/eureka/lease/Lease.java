/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.lease;

import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * 实例租约信息
 * Describes a time-based availability of a {@link T}. Purpose is to avoid
 * accumulation of instances in {@link AbstractInstanceRegistry} as result of ungraceful
 * shutdowns that is not uncommon in AWS environments.
 *
 * If a lease elapses without renewals, it will eventually expire consequently
 * marking the associated {@link T} for immediate eviction - this is similar to
 * an explicit cancellation except that there is no communication between the
 * {@link T} and {@link LeaseManager}.
 *
 * @author Karthik Ranganathan, Greg Kim
 */
public class Lease<T> {

    /**
     * 实例动作枚举
     */
    enum Action {
        //注冊
        Register,
        //下线
        Cancel,
        //心跳
        Renew
    };

    /**
     * 默认定时时长 90s
     */
    public static final int DEFAULT_DURATION_IN_SECS = 90;

    /**
     * 当前租约信息的持有者 默认 InstanceInfo
     */
    private T holder;
    /**
     * 租约到期服务下线时间戳
     */
    private long evictionTimestamp;
    /**
     * 服务注册时间戳
     */
    private long registrationTimestamp;

    /**
     * 服务上线时间戳
     */
    private long serviceUpTimestamp;
    /**
     * 服务最后更新时间戳
     */
    // Make it volatile so that the expiration task would see this quicker
    private volatile long lastUpdateTimestamp;

    /**
     * 固定时长，用于检测服务是否已经失效
     */
    private long duration;

    public Lease(T r, int durationInSecs) {
        holder = r;
        //注册时间戳
        registrationTimestamp = System.currentTimeMillis();
        //最后更新时间戳
        lastUpdateTimestamp = registrationTimestamp;
        duration = (durationInSecs * 1000);

    }

    /**
     * Renew the lease, use renewal duration if it was specified by the
     * associated {@link T} during registration, otherwise default duration is
     * {@link #DEFAULT_DURATION_IN_SECS}.
     */
    public void renew() {
        //更新服务最后更新的时间戳 （当前时间+给定定时）
        //最后更新时间=服务最后一次心跳时间+定时（默认90s）
        lastUpdateTimestamp = System.currentTimeMillis() + duration;

    }

    /**
     *
     * Cancels the lease by updating the eviction time.
     */
    public void cancel() {
        //更新服务离线时间戳
        if (evictionTimestamp <= 0) {
            evictionTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Mark the service as up. This will only take affect the first time called,
     * subsequent calls will be ignored.
     */
    public void serviceUp() {
        if (serviceUpTimestamp == 0) {
            serviceUpTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Set the leases service UP timestamp.
     */
    public void setServiceUpTimestamp(long serviceUpTimestamp) {
        this.serviceUpTimestamp = serviceUpTimestamp;
    }

    /**
     * 检测实例租约是否已过期
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     */
    public boolean isExpired() {
        return isExpired(0l);
    }

    /**
     * 检测实例租约是否已过期
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     *
     * Note that due to renew() doing the 'wrong" thing and setting lastUpdateTimestamp to +duration more than
     * what it should be, the expiry will actually be 2 * duration. This is a minor bug and should only affect
     * instances that ungracefully shutdown. Due to possible wide ranging impact to existing usage, this will
     * not be fixed.
     *
     * @param additionalLeaseMs any additional lease time to add to the lease evaluation in ms.
     */
    public boolean isExpired(long additionalLeaseMs) {
        //1 evictionTimestamp>0 服务已下线，租约过期
        //2 当前时间>(最后更新时间+额外给定租约时间（默认是0）+ 持续给定的时间（定值）) 表示租约已过期
        //lastUpdateTimestamp=最后的心跳时间+duration
        //2 当前时间>(最后心跳时间+duration+duration+additionalLeaseMs)
        return (evictionTimestamp > 0 || System.currentTimeMillis() > (lastUpdateTimestamp + duration + additionalLeaseMs));
    }

    /**
     * Gets the milliseconds since epoch when the lease was registered.
     *
     * @return the milliseconds since epoch when the lease was registered.
     */
    public long getRegistrationTimestamp() {
        return registrationTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was last renewed.
     * Note that the value returned here is actually not the last lease renewal time but the renewal + duration.
     *
     * @return the milliseconds since epoch when the lease was last renewed.
     */
    public long getLastRenewalTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was evicted.
     *
     * @return the milliseconds since epoch when the lease was evicted.
     */
    public long getEvictionTimestamp() {
        return evictionTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the service for the lease was marked as up.
     *
     * @return the milliseconds since epoch when the service for the lease was marked as up.
     */
    public long getServiceUpTimestamp() {
        return serviceUpTimestamp;
    }

    /**
     * Returns the holder of the lease.
     */
    public T getHolder() {
        return holder;
    }

}
