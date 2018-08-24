package com.netflix.discovery.shared.transport;

import com.netflix.discovery.shared.resolver.EurekaEndpoint;

/**
 * Eureka远程通讯客户端创建工厂接口，此接口为基础接口
 * A low level client factory interface. Not advised to be used by top level consumers.
 *
 * @author David Liu
 */
public interface TransportClientFactory {

    /**
     * 根据EurekaEndpoint 创建客户端
     * @param serviceUrl
     * @return
     */
    EurekaHttpClient newClient(EurekaEndpoint serviceUrl);

    /**
     * 关闭
     */
    void shutdown();

}
