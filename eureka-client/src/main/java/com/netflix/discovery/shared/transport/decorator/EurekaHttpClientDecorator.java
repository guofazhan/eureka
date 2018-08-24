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

package com.netflix.discovery.shared.transport.decorator;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;

/**
 * 抽象Eureka远程通讯客户端装饰器，使用设计模式-装饰器模式 为客户端添加新的功能
 * @author Tomasz Bak
 */
public abstract class EurekaHttpClientDecorator implements EurekaHttpClient {

    /**
     * 请求类型
     */
    public enum RequestType {
        //注册
        Register,
        //下线
        Cancel,
        //心跳
        SendHeartBeat,
        //状态更新
        StatusUpdate,
        DeleteStatusOverride,
        GetApplications,
        GetDelta,
        GetVip,
        GetSecureVip,
        GetApplication,
        GetInstance,
        GetApplicationInstance
    }

    /**
     * 请求执行接口，负责执行请求
     * @param <R>
     */
    public interface RequestExecutor<R> {
        /**
         * 执行请求并返回响应信息
         * @param delegate 目标的客户端
         * @return
         */
        EurekaHttpResponse<R> execute(EurekaHttpClient delegate);

        /**
         * 请求的类型
         * @return
         */
        RequestType getRequestType();
    }

    /**
     * 抽象的执行方法，由子装饰器实现，附加其它功能
     * @param requestExecutor
     * @param <R>
     * @return
     */
    protected abstract <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor);

    @Override
    public EurekaHttpResponse<Void> register(final InstanceInfo info) {
        //创建一个注册请求的执行器，并执行
        return execute(new RequestExecutor<Void>() {
            @Override
            public EurekaHttpResponse<Void> execute(EurekaHttpClient delegate) {
                return delegate.register(info);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.Register;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Void> cancel(final String appName, final String id) {
        //创建一个下线请求的执行，并执行
        return execute(new RequestExecutor<Void>() {
            @Override
            public EurekaHttpResponse<Void> execute(EurekaHttpClient delegate) {
                return delegate.cancel(appName, id);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.Cancel;
            }
        });
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> sendHeartBeat(final String appName,
                                                          final String id,
                                                          final InstanceInfo info,
                                                          final InstanceStatus overriddenStatus) {
        return execute(new RequestExecutor<InstanceInfo>() {
            @Override
            public EurekaHttpResponse<InstanceInfo> execute(EurekaHttpClient delegate) {
                return delegate.sendHeartBeat(appName, id, info, overriddenStatus);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.SendHeartBeat;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Void> statusUpdate(final String appName, final String id, final InstanceStatus newStatus, final InstanceInfo info) {
        return execute(new RequestExecutor<Void>() {
            @Override
            public EurekaHttpResponse<Void> execute(EurekaHttpClient delegate) {
                return delegate.statusUpdate(appName, id, newStatus, info);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.StatusUpdate;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Void> deleteStatusOverride(final String appName, final String id, final InstanceInfo info) {
        return execute(new RequestExecutor<Void>() {
            @Override
            public EurekaHttpResponse<Void> execute(EurekaHttpClient delegate) {
                return delegate.deleteStatusOverride(appName, id, info);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.DeleteStatusOverride;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Applications> getApplications(final String... regions) {
        return execute(new RequestExecutor<Applications>() {
            @Override
            public EurekaHttpResponse<Applications> execute(EurekaHttpClient delegate) {
                return delegate.getApplications(regions);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetApplications;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Applications> getDelta(final String... regions) {
        return execute(new RequestExecutor<Applications>() {
            @Override
            public EurekaHttpResponse<Applications> execute(EurekaHttpClient delegate) {
                return delegate.getDelta(regions);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetDelta;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Applications> getVip(final String vipAddress, final String... regions) {
        return execute(new RequestExecutor<Applications>() {
            @Override
            public EurekaHttpResponse<Applications> execute(EurekaHttpClient delegate) {
                return delegate.getVip(vipAddress, regions);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetVip;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Applications> getSecureVip(final String secureVipAddress, final String... regions) {
        return execute(new RequestExecutor<Applications>() {
            @Override
            public EurekaHttpResponse<Applications> execute(EurekaHttpClient delegate) {
                return delegate.getVip(secureVipAddress, regions);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetSecureVip;
            }
        });
    }

    @Override
    public EurekaHttpResponse<Application> getApplication(final String appName) {
        return execute(new RequestExecutor<Application>() {
            @Override
            public EurekaHttpResponse<Application> execute(EurekaHttpClient delegate) {
                return delegate.getApplication(appName);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetApplication;
            }
        });
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(final String id) {
        return execute(new RequestExecutor<InstanceInfo>() {
            @Override
            public EurekaHttpResponse<InstanceInfo> execute(EurekaHttpClient delegate) {
                return delegate.getInstance(id);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetInstance;
            }
        });
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(final String appName, final String id) {
        return execute(new RequestExecutor<InstanceInfo>() {
            @Override
            public EurekaHttpResponse<InstanceInfo> execute(EurekaHttpClient delegate) {
                return delegate.getInstance(appName, id);
            }

            @Override
            public RequestType getRequestType() {
                return RequestType.GetApplicationInstance;
            }
        });
    }
}
