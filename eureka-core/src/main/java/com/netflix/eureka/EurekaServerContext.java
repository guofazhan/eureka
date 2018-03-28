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
 * eureka server 上下文环境信息
 * @author David Liu
 */
public interface EurekaServerContext {

    /**
     * 初始化方法
     * @throws Exception
     */
    void initialize() throws Exception;

    /**
     * 关闭
     * @throws Exception
     */
    void shutdown() throws Exception;

    /**
     * 获取 eureka server配置信息
     * @return
     */
    EurekaServerConfig getServerConfig();

    /**
     * Eureka-Server 集群节点集合
     * @return
     */
    PeerEurekaNodes getPeerEurekaNodes();

    /**
     * Eureka-Server 请求和响应编解码器
     * @return
     */
    ServerCodecs getServerCodecs();

    /**
     *  Eureka-Server 应用对象信息的注册表
     * @return
     */
    PeerAwareInstanceRegistry getRegistry();

    /**
     * Eureka-Server  应用信息管理器
     * @return
     */
    ApplicationInfoManager getApplicationInfoManager();

}
