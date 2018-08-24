/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.resolver;

/**
 * Eureka Server终端信息接口，包含 url host，port
 */
public interface EurekaEndpoint extends Comparable<Object> {

    /**
     * 获取URL
     * @return
     */
    String getServiceUrl();

    /**
     * @deprecated use {@link #getNetworkAddress()}
     */
    @Deprecated
    String getHostName();

    /**
     * 获取 IP
     * @return
     */
    String getNetworkAddress();

    /**
     * 端口
     * @return
     */
    int getPort();

    /**
     * 是否为安全连接 ，https与http
     * @return
     */
    boolean isSecure();

    /**
     * 获取相对URL
     * @return
     */
    String getRelativeUri();

}
