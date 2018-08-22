/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.eureka;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

/**
 * Eureka 服务端上下问环境接口
 * @author David Liu
 */
public interface EurekaServerContext {

    /**
     * 初始化
     * @throws Exception
     */
    void initialize() throws Exception;

    /**
     * 关闭
     * @throws Exception
     */
    void shutdown() throws Exception;

    /**
     * 获取服务端配置信息
     * @return
     */
    EurekaServerConfig getServerConfig();

    /**
     * 获取服务端集群节点信息
     * @return
     */
    PeerEurekaNodes getPeerEurekaNodes();

    /**
     * 获取服务端对外提供编码解码器
     * @return
     */
    ServerCodecs getServerCodecs();

    /**
     *
     * @return
     */
    PeerAwareInstanceRegistry getRegistry();

    /**
     * 获取服务端对应的客户端管理器（Eureka服务端同时也是一个客户端与其它节点交互）
     * @return
     */
    ApplicationInfoManager getApplicationInfoManager();

}
