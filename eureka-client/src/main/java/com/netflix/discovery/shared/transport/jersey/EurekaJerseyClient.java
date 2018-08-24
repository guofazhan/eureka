package com.netflix.discovery.shared.transport.jersey;

import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * Eureka远程通讯之http客户端（Jersey）接口
 * @author David Liu
 */
public interface EurekaJerseyClient {

    /**
     * 获取 ApacheHttpClient4客户端
     * @return
     */
    ApacheHttpClient4 getClient();

    /**
     * 销毁资源
     * Clean up resources.
     */
    void destroyResources();
}
