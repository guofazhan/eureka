package com.netflix.discovery.shared.transport;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;

/**
 * Eureka远程通讯客户端接口，负责与server-client server-server之间的通讯 REST API 风格
 * Low level Eureka HTTP client API.
 *
 * @author Tomasz Bak
 */
public interface EurekaHttpClient {

    /**
     * 实例注册
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> register(InstanceInfo info);

    /**
     * 实例下线
     * @param appName
     * @param id
     * @return
     */
    EurekaHttpResponse<Void> cancel(String appName, String id);

    /**
     * 心跳发送
     * @param appName
     * @param id
     * @param info
     * @param overriddenStatus
     * @return
     */
    EurekaHttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus);

    /**
     *  状态更新
     * @param appName
     * @param id
     * @param newStatus
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus, InstanceInfo info);

    /**
     * 状态删除
     * @param appName
     * @param id
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info);

    /**
     * 获取注册列表
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getApplications(String... regions);

    /**
     * 获取增量注册列表
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getDelta(String... regions);

    /**
     * 根据 vip 获取注册列表
     * @param vipAddress
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getVip(String vipAddress, String... regions);

    /**
     * 根据secureVipAddress 获取注册列表
     * @param secureVipAddress
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getSecureVip(String secureVipAddress, String... regions);

    /**
     * 获取注册集群信息，根据集群ID
     * @param appName
     * @return
     */
    EurekaHttpResponse<Application> getApplication(String appName);

    /**
     * 获取实例
     * @param appName
     * @param id
     * @return
     */
    EurekaHttpResponse<InstanceInfo> getInstance(String appName, String id);

    /**
     * 获取实例
     * @param id
     * @return
     */
    EurekaHttpResponse<InstanceInfo> getInstance(String id);

    /**
     * 关闭
     */
    void shutdown();
}
