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

package com.netflix.discovery;

import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRATION_PREFIX;
import static com.netflix.discovery.EurekaClientNames.METRIC_REGISTRY_PREFIX;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckCallbackToHandlerBridge;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpClients;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaTransportConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.Jersey1DiscoveryClientOptionalArgs;
import com.netflix.discovery.shared.transport.jersey.Jersey1TransportClientFactories;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.discovery.util.ThresholdLevelsMetric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;

/**
 * The class that is instrumental for interactions with <tt>Eureka Server</tt>.
 *
 * <p>
 * <tt>Eureka Client</tt> is responsible for a) <em>Registering</em> the
 * instance with <tt>Eureka Server</tt> b) <em>Renewal</em>of the lease with
 * <tt>Eureka Server</tt> c) <em>Cancellation</em> of the lease from
 * <tt>Eureka Server</tt> during shutdown
 * <p>
 * d) <em>Querying</em> the list of services/instances registered with
 * <tt>Eureka Server</tt>
 * <p>
 *
 * <p>
 * <tt>Eureka Client</tt> needs a configured list of <tt>Eureka Server</tt>
 * {@link java.net.URL}s to talk to.These {@link java.net.URL}s are typically amazon elastic eips
 * which do not change. All of the functions defined above fail-over to other
 * {@link java.net.URL}s specified in the list in the case of failure.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * @author Spencer Gibb
 *
 */
@Singleton
public class DiscoveryClient implements EurekaClient {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryClient.class);

    // Constants
    public static final String HTTP_X_DISCOVERY_ALLOW_REDIRECT = "X-Discovery-AllowRedirect";

    private static final String VALUE_DELIMITER = ",";
    private static final String COMMA_STRING = VALUE_DELIMITER;

    /**
     * eureka 客户端配置
     * @deprecated here for legacy support as the client config has moved to be an instance variable
     */
    @Deprecated
    private static EurekaClientConfig staticClientConfig;

    /**
     * 定时器线程名前缀
     */
    // Timers
    private static final String PREFIX = "DiscoveryClient_";

    private final Counter RECONCILE_HASH_CODES_MISMATCH = Monitors.newCounter(PREFIX + "ReconcileHashCodeMismatch");
    private final com.netflix.servo.monitor.Timer FETCH_REGISTRY_TIMER = Monitors
            .newTimer(PREFIX + "FetchRegistry");
    private final Counter REREGISTER_COUNTER = Monitors.newCounter(PREFIX
            + "Reregister");

    // instance variables
    /**
     * 定时任务执行器
     * A scheduler to be used for the following 3 tasks:
     * - updating service urls
     * - scheduling a TimedSuperVisorTask
     */
    private final ScheduledExecutorService scheduler;
    /**
     * 心跳线程池
     */
    // additional executors for supervised subtasks
    private final ThreadPoolExecutor heartbeatExecutor;
    /**
     * 缓存刷新线程池
     */
    private final ThreadPoolExecutor cacheRefreshExecutor;

    /**
     * 健康检查处理器工厂
     */
    private final Provider<HealthCheckHandler> healthCheckHandlerProvider;

    /**
     * 健康检查回调函数工厂
     */
    private final Provider<HealthCheckCallback> healthCheckCallbackProvider;
    /**
     * 当前eureka client注册前处理器，用于注册前业务处理
     */
    private final PreRegistrationHandler preRegistrationHandler;
    /**
     * eureka Server 端注册的应用集合 本地緩存
     */
    private final AtomicReference<Applications> localRegionApps = new AtomicReference<Applications>();
    private final Lock fetchRegistryUpdateLock = new ReentrantLock();
    /**
     * 拉取注册信息次数计数器
     */
    // monotonically increasing generation counter to ensure stale threads do not reset registry to an older version
    private final AtomicLong fetchRegistryGeneration;
    private final ApplicationInfoManager applicationInfoManager;
    private final InstanceInfo instanceInfo;
    private final AtomicReference<String> remoteRegionsToFetch;
    private final AtomicReference<String[]> remoteRegionsRef;
    private final InstanceRegionChecker instanceRegionChecker;

    private final EndpointUtils.ServiceUrlRandomizer urlRandomizer;
    private final Provider<BackupRegistry> backupRegistryProvider;
    private final EurekaTransport eurekaTransport;

    private volatile HealthCheckHandler healthCheckHandler;
    private volatile Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<>();
    /**
     * 最后一次获取的当前实例在eureka Server上的状态（最新的实例远程状态）
     */
    private volatile InstanceInfo.InstanceStatus lastRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
    private final CopyOnWriteArraySet<EurekaEventListener> eventListeners = new CopyOnWriteArraySet<>();

    private String appPathIdentifier;
    private ApplicationInfoManager.StatusChangeListener statusChangeListener;

    private InstanceInfoReplicator instanceInfoReplicator;

    /**
     * Eureka-Server 拉取的注册列表数
     */
    private volatile int registrySize = 0;
    /**
     * 最后成功从 Eureka-Server 拉取注册信息时间戳
     */
    private volatile long lastSuccessfulRegistryFetchTimestamp = -1;
    /**
     * 最后成功向 Eureka-Server 心跳时间戳
     */
    private volatile long lastSuccessfulHeartbeatTimestamp = -1;
    /**
     * 心跳监控
     */
    private final ThresholdLevelsMetric heartbeatStalenessMonitor;
    /**
     * 拉取监控
     */
    private final ThresholdLevelsMetric registryStalenessMonitor;

    /**
     * 关闭状态
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * 客户端配置信息
     */
    protected final EurekaClientConfig clientConfig;
    /**
     * 客户端远程传输配置信息
     */
    protected final EurekaTransportConfig transportConfig;

    /**
     * 初始化完成时间戳
     */
    private final long initTimestampMs;

    /**
     * 远程通信组件类
     */
    private static final class EurekaTransport {
        private ClosableResolver bootstrapResolver;
        private TransportClientFactory transportClientFactory;

        private EurekaHttpClient registrationClient;
        private EurekaHttpClientFactory registrationClientFactory;

        private EurekaHttpClient queryClient;
        private EurekaHttpClientFactory queryClientFactory;

        void shutdown() {
            if (registrationClientFactory != null) {
                registrationClientFactory.shutdown();
            }

            if (queryClientFactory != null) {
                queryClientFactory.shutdown();
            }

            if (registrationClient != null) {
                registrationClient.shutdown();
            }

            if (queryClient != null) {
                queryClient.shutdown();
            }

            if (transportClientFactory != null) {
                transportClientFactory.shutdown();
            }

            if (bootstrapResolver != null) {
                bootstrapResolver.shutdown();
            }
        }
    }

    /**
     * 远程通信组件参数类
     */
    public static class DiscoveryClientOptionalArgs extends Jersey1DiscoveryClientOptionalArgs {

    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config) {
        this(myInfo, config, null);
    }

    /**
     * Assumes applicationInfoManager is already initialized
     *
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    /**
     * @deprecated use constructor that takes ApplicationInfoManager instead of InstanceInfo directly
     */
    @Deprecated
    public DiscoveryClient(InstanceInfo myInfo, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(ApplicationInfoManager.getInstance(), config, args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config) {
        this(applicationInfoManager, config, null);
    }

    /**
     * @deprecated use the version that take {@link com.netflix.discovery.AbstractDiscoveryClientOptionalArgs} instead
     */
    @Deprecated
    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, DiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, (AbstractDiscoveryClientOptionalArgs) args);
    }

    public DiscoveryClient(ApplicationInfoManager applicationInfoManager, final EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args) {
        this(applicationInfoManager, config, args, new Provider<BackupRegistry>() {
            private volatile BackupRegistry backupRegistryInstance;

            @Override
            public synchronized BackupRegistry get() {
                if (backupRegistryInstance == null) {
                    String backupRegistryClassName = config.getBackupRegistryImpl();
                    if (null != backupRegistryClassName) {
                        try {
                            backupRegistryInstance = (BackupRegistry) Class.forName(backupRegistryClassName).newInstance();
                            logger.info("Enabled backup registry of type {}", backupRegistryInstance.getClass());
                        } catch (InstantiationException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (IllegalAccessException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        } catch (ClassNotFoundException e) {
                            logger.error("Error instantiating BackupRegistry.", e);
                        }
                    }

                    if (backupRegistryInstance == null) {
                        logger.warn("Using default backup registry implementation which does not do anything.");
                        backupRegistryInstance = new NotImplementedRegistryImpl();
                    }
                }

                return backupRegistryInstance;
            }
        });
    }

    @Inject
    DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args,
                    Provider<BackupRegistry> backupRegistryProvider) {
        if (args != null) {
            this.healthCheckHandlerProvider = args.healthCheckHandlerProvider;
            this.healthCheckCallbackProvider = args.healthCheckCallbackProvider;
            this.eventListeners.addAll(args.getEventListeners());
            this.preRegistrationHandler = args.preRegistrationHandler;
        } else {
            this.healthCheckCallbackProvider = null;
            this.healthCheckHandlerProvider = null;
            this.preRegistrationHandler = null;
        }
        
        this.applicationInfoManager = applicationInfoManager;
        InstanceInfo myInfo = applicationInfoManager.getInfo();

        clientConfig = config;
        staticClientConfig = clientConfig;
        transportConfig = config.getTransportConfig();
        instanceInfo = myInfo;
        if (myInfo != null) {
            appPathIdentifier = instanceInfo.getAppName() + "/" + instanceInfo.getId();
        } else {
            logger.warn("Setting instanceInfo to a passed in null value");
        }

        //备份注册中心接口
        this.backupRegistryProvider = backupRegistryProvider;
        //初始化 InstanceInfoBasedUrlRandomizer
        this.urlRandomizer = new EndpointUtils.InstanceInfoBasedUrlRandomizer(instanceInfo);
        //初始化 Applications 在本地的缓存为空，定时任务间隔从 Eureka-Server 拉取注册应用信息到本地缓存
        localRegionApps.set(new Applications());

        fetchRegistryGeneration = new AtomicLong(0);

        remoteRegionsToFetch = new AtomicReference<String>(clientConfig.fetchRegistryForRemoteRegions());
        remoteRegionsRef = new AtomicReference<>(remoteRegionsToFetch.get() == null ? null : remoteRegionsToFetch.get().split(","));

        if (config.shouldFetchRegistry()) {
            this.registryStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRY_PREFIX + "lastUpdateSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.registryStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        if (config.shouldRegisterWithEureka()) {
            this.heartbeatStalenessMonitor = new ThresholdLevelsMetric(this, METRIC_REGISTRATION_PREFIX + "lastHeartbeatSec_", new long[]{15L, 30L, 60L, 120L, 240L, 480L});
        } else {
            this.heartbeatStalenessMonitor = ThresholdLevelsMetric.NO_OP_METRIC;
        }

        logger.info("Initializing Eureka in region {}", clientConfig.getRegion());

        if (!config.shouldRegisterWithEureka() && !config.shouldFetchRegistry()) {
            logger.info("Client configured to neither register nor query for data.");
            scheduler = null;
            heartbeatExecutor = null;
            cacheRefreshExecutor = null;
            eurekaTransport = null;
            instanceRegionChecker = new InstanceRegionChecker(new PropertyBasedAzToRegionMapper(config), clientConfig.getRegion());

            // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
            // to work with DI'd DiscoveryClient
            DiscoveryManager.getInstance().setDiscoveryClient(this);
            DiscoveryManager.getInstance().setEurekaClientConfig(config);

            initTimestampMs = System.currentTimeMillis();
            logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                    initTimestampMs, this.getApplications().size());

            return;  // no need to setup up an network tasks and we are done
        }

        try {
            //定时任务线程池，初始化大小为 2  分别供心跳任务以及缓存刷新任务定时调用
            // default size of 2 - 1 each for heartbeat and cacheRefresh
            scheduler = Executors.newScheduledThreadPool(2,
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-%d")
                            .setDaemon(true)
                            .build());

            //初始化心跳线程池 用于执行心跳请求 核心线程池大小为1 最大线程池可配置
            heartbeatExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getHeartbeatExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-HeartbeatExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            //初始化缓存刷新线程池 用户执行缓存刷新请求 核心线程池大小为1 最大线程池可配置
            cacheRefreshExecutor = new ThreadPoolExecutor(
                    1, clientConfig.getCacheRefreshExecutorThreadPoolSize(), 0, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactoryBuilder()
                            .setNameFormat("DiscoveryClient-CacheRefreshExecutor-%d")
                            .setDaemon(true)
                            .build()
            );  // use direct handoff

            //初始化Eureka Client 网络通信
            eurekaTransport = new EurekaTransport();
            //初始化Eureka Client 网络通信
            scheduleServerEndpointTask(eurekaTransport, args);

            AzToRegionMapper azToRegionMapper;
            if (clientConfig.shouldUseDnsForFetchingServiceUrls()) {
                azToRegionMapper = new DNSBasedAzToRegionMapper(clientConfig);
            } else {
                azToRegionMapper = new PropertyBasedAzToRegionMapper(clientConfig);
            }
            if (null != remoteRegionsToFetch.get()) {
                azToRegionMapper.setRegionsToFetch(remoteRegionsToFetch.get().split(","));
            }
            //应用实例信息区域( region )校验
            instanceRegionChecker = new InstanceRegionChecker(azToRegionMapper, clientConfig.getRegion());
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DiscoveryClient!", e);
        }


        //调用 #fetchRegistry(false) 方法，从 Eureka-Server 初始拉取注册信息
        if (clientConfig.shouldFetchRegistry() && !fetchRegistry(false)) {
            //调用 #fetchRegistryFromBackup() 方法，若初始拉取注册信息失败，从备份注册中心获取
            fetchRegistryFromBackup();
        }

        //当前注册前置处理器不为null时，执行前置处理器
        //执行向 Eureka-Server 注册之前的处理器
        // call and execute the pre registration handler before all background tasks (inc registration) is started
        if (this.preRegistrationHandler != null) {
            this.preRegistrationHandler.beforeRegistration();
        }

        // shouldRegisterWithEureka()是否向 Eureka-Server 注册自身服务
        if (clientConfig.shouldRegisterWithEureka() && clientConfig.shouldEnforceRegistrationAtInit()) {
            try {
                //执行向 Eureka-Server 注册自身服务
                if (!register() ) {
                    throw new IllegalStateException("Registration error at startup. Invalid server response.");
                }
            } catch (Throwable th) {
                logger.error("Registration error at startup: {}", th.getMessage());
                throw new IllegalStateException(th);
            }
        }

        //线程池创建完毕后，初始化定时任务信息
        // finally, init the schedule tasks (e.g. cluster resolvers, heartbeat, instanceInfo replicator, fetch
        initScheduledTasks();

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register timers", e);
        }

        // This is a bit of hack to allow for existing code using DiscoveryManager.getInstance()
        // to work with DI'd DiscoveryClient
        DiscoveryManager.getInstance().setDiscoveryClient(this);
        DiscoveryManager.getInstance().setEurekaClientConfig(config);

        //写入初始化完成后时间戳
        initTimestampMs = System.currentTimeMillis();
        logger.info("Discovery Client initialized at timestamp {} with initial instances count: {}",
                initTimestampMs, this.getApplications().size());
    }

    /**
     * 初始化eureka Client 远程通信（HTTP）组件
     * @param eurekaTransport
     * @param args
     */
    private void scheduleServerEndpointTask(EurekaTransport eurekaTransport,
                                            AbstractDiscoveryClientOptionalArgs args) {

            
        Collection<?> additionalFilters = args == null
                ? Collections.emptyList()
                : args.additionalFilters;

        EurekaJerseyClient providedJerseyClient = args == null
                ? null
                : args.eurekaJerseyClient;
        
        TransportClientFactories argsTransportClientFactories = null;
        if (args != null && args.getTransportClientFactories() != null) {
            argsTransportClientFactories = args.getTransportClientFactories();
        }
        
        // Ignore the raw types warnings since the client filter interface changed between jersey 1/2
        @SuppressWarnings("rawtypes")
        TransportClientFactories transportClientFactories = argsTransportClientFactories == null
                ? new Jersey1TransportClientFactories()
                : argsTransportClientFactories;
                
        Optional<SSLContext> sslContext = args == null
                ? Optional.empty()
                : args.getSSLContext();
        Optional<HostnameVerifier> hostnameVerifier = args == null
                ? Optional.empty()
                : args.getHostnameVerifier();

        // If the transport factory was not supplied with args, assume they are using jersey 1 for passivity
        eurekaTransport.transportClientFactory = providedJerseyClient == null
                ? transportClientFactories.newTransportClientFactory(clientConfig, additionalFilters, applicationInfoManager.getInfo(), sslContext, hostnameVerifier)
                : transportClientFactories.newTransportClientFactory(additionalFilters, providedJerseyClient);

        ApplicationsResolver.ApplicationsSource applicationsSource = new ApplicationsResolver.ApplicationsSource() {
            @Override
            public Applications getApplications(int stalenessThreshold, TimeUnit timeUnit) {
                long thresholdInMs = TimeUnit.MILLISECONDS.convert(stalenessThreshold, timeUnit);
                long delay = getLastSuccessfulRegistryFetchTimePeriod();
                if (delay > thresholdInMs) {
                    logger.info("Local registry is too stale for local lookup. Threshold:{}, actual:{}",
                            thresholdInMs, delay);
                    return null;
                } else {
                    return localRegionApps.get();
                }
            }
        };

        eurekaTransport.bootstrapResolver = EurekaHttpClients.newBootstrapResolver(
                clientConfig,
                transportConfig,
                eurekaTransport.transportClientFactory,
                applicationInfoManager.getInfo(),
                applicationsSource
        );

        if (clientConfig.shouldRegisterWithEureka()) {
            EurekaHttpClientFactory newRegistrationClientFactory = null;
            EurekaHttpClient newRegistrationClient = null;
            try {
                newRegistrationClientFactory = EurekaHttpClients.registrationClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        transportConfig
                );
                newRegistrationClient = newRegistrationClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.registrationClientFactory = newRegistrationClientFactory;
            eurekaTransport.registrationClient = newRegistrationClient;
        }

        // new method (resolve from primary servers for read)
        // Configure new transport layer (candidate for injecting in the future)
        if (clientConfig.shouldFetchRegistry()) {
            EurekaHttpClientFactory newQueryClientFactory = null;
            EurekaHttpClient newQueryClient = null;
            try {
                newQueryClientFactory = EurekaHttpClients.queryClientFactory(
                        eurekaTransport.bootstrapResolver,
                        eurekaTransport.transportClientFactory,
                        clientConfig,
                        transportConfig,
                        applicationInfoManager.getInfo(),
                        applicationsSource
                );
                newQueryClient = newQueryClientFactory.newClient();
            } catch (Exception e) {
                logger.warn("Transport initialization failure", e);
            }
            eurekaTransport.queryClientFactory = newQueryClientFactory;
            eurekaTransport.queryClient = newQueryClient;
        }
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return clientConfig;
    }
    
    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    @Override
    public Applications getApplications() {
        return localRegionApps.get();
    }

    @Override
    public Applications getApplicationsForARegion(@Nullable String region) {
        if (instanceRegionChecker.isLocalRegion(region)) {
            return localRegionApps.get();
        } else {
            return remoteRegionVsApps.get(region);
        }
    }

    public Set<String> getAllKnownRegions() {
        String localRegion = instanceRegionChecker.getLocalRegion();
        if (!remoteRegionVsApps.isEmpty()) {
            Set<String> regions = remoteRegionVsApps.keySet();
            Set<String> toReturn = new HashSet<String>(regions);
            toReturn.add(localRegion);
            return toReturn;
        } else {
            return Collections.singleton(localRegion);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(java.lang.String)
     */
    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        List<InstanceInfo> instancesList = new ArrayList<InstanceInfo>();
        for (Application app : this.getApplications()
                .getRegisteredApplications()) {
            InstanceInfo instanceInfo = app.getByInstanceId(id);
            if (instanceInfo != null) {
                instancesList.add(instanceInfo);
            }
        }
        return instancesList;
    }

    /**
     * Register {@link HealthCheckCallback} with the eureka client.
     *
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     *
     * @deprecated Use
     */
    @Deprecated
    @Override
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        if (instanceInfo == null) {
            logger.error("Cannot register a listener for instance info since it is null!");
        }
        if (callback != null) {
            healthCheckHandler = new HealthCheckCallbackToHandlerBridge(callback);
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        if (instanceInfo == null) {
            logger.error("Cannot register a healthcheck handler when instance info is null!");
        }
        if (healthCheckHandler != null) {
            this.healthCheckHandler = healthCheckHandler;
            // schedule an onDemand update of the instanceInfo when a new healthcheck handler is registered
            if (instanceInfoReplicator != null) {
                instanceInfoReplicator.onDemandUpdate();
            }
        }
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        this.eventListeners.add(eventListener);
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return this.eventListeners.remove(eventListener);
    }

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return getInstancesByVipAddress(vipAddress, secure, instanceRegionChecker.getLocalRegion());
    }

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress - The VIP address to match the instances for.
     * @param secure - true if it is a secure vip address, false otherwise
     * @param region - region from which the instances are to be fetched. If <code>null</code> then local region is
     *               assumed.
     *
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure,
                                                       @Nullable String region) {
        if (vipAddress == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address cannot be null");
        }
        Applications applications;
        if (instanceRegionChecker.isLocalRegion(region)) {
            applications = this.localRegionApps.get();
        } else {
            applications = remoteRegionVsApps.get(region);
            if (null == applications) {
                logger.debug("No applications are defined for region {}, so returning an empty instance list for vip "
                        + "address {}.", region, vipAddress);
                return Collections.emptyList();
            }
        }

        if (!secure) {
            return applications.getInstancesByVirtualHostName(vipAddress);
        } else {
            return applications.getInstancesBySecureVirtualHostName(vipAddress);

        }

    }

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress
     *            - The VIP address to match the instances for.
     * @param appName
     *            - The applicationName to match the instances for.
     * @param secure
     *            - true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     */
    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(
            String vipAddress, String appName, boolean secure) {

        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        if (vipAddress == null && appName == null) {
            throw new IllegalArgumentException(
                    "Supplied VIP Address and application name cannot both be null");
        } else if (vipAddress != null && appName == null) {
            return getInstancesByVipAddress(vipAddress, secure);
        } else if (vipAddress == null && appName != null) {
            Application application = getApplication(appName);
            if (application != null) {
                result = application.getInstances();
            }
            return result;
        }

        String instanceVipAddress;
        for (Application app : getApplications().getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                if (secure) {
                    instanceVipAddress = instance.getSecureVipAddress();
                } else {
                    instanceVipAddress = instance.getVIPAddress();
                }
                if (instanceVipAddress == null) {
                    continue;
                }
                String[] instanceVipAddresses = instanceVipAddress
                        .split(COMMA_STRING);

                // If the VIP Address is delimited by a comma, then consider to
                // be a list of VIP Addresses.
                // Try to match at least one in the list, if it matches then
                // return the instance info for the same
                for (String vipAddressFromList : instanceVipAddresses) {
                    if (vipAddress.equalsIgnoreCase(vipAddressFromList.trim())
                            && appName.equalsIgnoreCase(instance.getAppName())) {
                        result.add(instance);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.discovery.shared.LookupService#getNextServerFromEureka(java
     * .lang.String, boolean)
     */
    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        List<InstanceInfo> instanceInfoList = this.getInstancesByVipAddress(
                virtualHostname, secure);
        if (instanceInfoList == null || instanceInfoList.isEmpty()) {
            throw new RuntimeException("No matches for the virtual host name :"
                    + virtualHostname);
        }
        Applications apps = this.localRegionApps.get();
        int index = (int) (apps.getNextIndex(virtualHostname,
                secure).incrementAndGet() % instanceInfoList.size());
        return instanceInfoList.get(index);
    }

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl
     *            - The string representation of the service url.
     * @return - The registry information containing all applications.
     */
    @Override
    public Applications getApplications(String serviceUrl) {
        try {
            EurekaHttpResponse<Applications> response = clientConfig.getRegistryRefreshSingleVipAddress() == null
                    ? eurekaTransport.queryClient.getApplications()
                    : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress());
            if (response.getStatusCode() == 200) {
                logger.debug(PREFIX + "{} -  refresh status: {}", appPathIdentifier, response.getStatusCode());
                return response.getEntity();
            }
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier, response.getStatusCode());
        } catch (Throwable th) {
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier, th.getMessage(), th);
        }
        return null;
    }

    /**
     * 注册当前服务到uereka server上
     * Register with the eureka service by making the appropriate REST call.
     */
    boolean register() throws Throwable {
        logger.info(PREFIX + "{}: registering service...", appPathIdentifier);
        EurekaHttpResponse<Void> httpResponse;
        try {
            //将当前服务实例信息（instanceInfo）通过远程通信组件（httpclient）发送eureka server
            httpResponse = eurekaTransport.registrationClient.register(instanceInfo);
        } catch (Exception e) {
            logger.warn(PREFIX + "{} - registration failed {}", appPathIdentifier, e.getMessage(), e);
            throw e;
        }
        if (logger.isInfoEnabled()) {
            logger.info(PREFIX + "{} - registration status: {}", appPathIdentifier, httpResponse.getStatusCode());
        }
        //返回注册调用状态
        return httpResponse.getStatusCode() == 204;
    }

    /**
     * 心跳发送方法
     * eureka client 向eureka server端发起续租请求的具体实现方法
     * Renew with the eureka service by making the appropriate REST call
     */
    boolean renew() {
        EurekaHttpResponse<InstanceInfo> httpResponse;
        try {
            //向eureka server 发送续租请求，参数 appname instanceId  instanceInfo
            httpResponse = eurekaTransport.registrationClient.sendHeartBeat(instanceInfo.getAppName(), instanceInfo.getId(), instanceInfo, null);
            logger.debug(PREFIX + "{} - Heartbeat status: {}", appPathIdentifier, httpResponse.getStatusCode());
            if (httpResponse.getStatusCode() == 404) {
                //当前续租请求返回404时，表示当前client在server不存在租约信息，重新发起注册请求
                //此处可参见InstanceResource renewLease()方法
                REREGISTER_COUNTER.increment();
                logger.info(PREFIX + "{} - Re-registering apps/{}", appPathIdentifier, instanceInfo.getAppName());
                long timestamp = instanceInfo.setIsDirtyWithTime();
                //向eureka Server发送注册请求
                boolean success = register();
                if (success) {
                    instanceInfo.unsetIsDirty(timestamp);
                }
                return success;
            }
            return httpResponse.getStatusCode() == 200;
        } catch (Throwable e) {
            logger.error(PREFIX + "{} - was unable to send heartbeat!", appPathIdentifier, e);
            return false;
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     *
     * @param instanceZone The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(clientConfig, instanceZone, preferSameZone);
    }

    /**
     * 关闭当前Eureka Client
     * Shuts down Eureka Client. Also sends a deregistration request to the
     * eureka server.
     */
    @PreDestroy
    @Override
    public synchronized void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("Shutting down DiscoveryClient ...");

            if (statusChangeListener != null && applicationInfoManager != null) {
                applicationInfoManager.unregisterStatusChangeListener(statusChangeListener.getId());
            }

            //取消，关闭定时任务线程
            cancelScheduledTasks();

            //eureka.registration.enabled = true 且eureka.shouldUnregisterOnShutdown = true条件成立时
			// 向 eureka server端发起客户端下线请求
            // If APPINFO was registered
            if (applicationInfoManager != null
                    && clientConfig.shouldRegisterWithEureka()
                    && clientConfig.shouldUnregisterOnShutdown()) {
                applicationInfoManager.setInstanceStatus(InstanceStatus.DOWN);
                //发起下线请求
                unregister();
            }

            if (eurekaTransport != null) {
                eurekaTransport.shutdown();
            }

            heartbeatStalenessMonitor.shutdown();
            registryStalenessMonitor.shutdown();

            logger.info("Completed shut down of DiscoveryClient");
        }
    }

    /**
     * unregister w/ the eureka service.
     */
    void unregister() {
        // It can be null if shouldRegisterWithEureka == false
        if(eurekaTransport != null && eurekaTransport.registrationClient != null) {
            try {
                logger.info("Unregistering ...");
                EurekaHttpResponse<Void> httpResponse = eurekaTransport.registrationClient.cancel(instanceInfo.getAppName(), instanceInfo.getId());
                logger.info(PREFIX + "{} - deregister  status: {}", appPathIdentifier, httpResponse.getStatusCode());
            } catch (Exception e) {
                logger.error(PREFIX + "{} - de-registration failed{}", appPathIdentifier, e.getMessage(), e);
            }
        }
    }

    /**
     * 拉取注册服务信息，从eureka server端拉取注册注册节点信息
     * 从 Eureka-Server 获取注册信息( 根据条件判断，可能是全量，也可能是增量 )
     * Fetches the registry information.
     *
     * <p>
     * This method tries to get only deltas after the first fetch unless there
     * is an issue in reconciling eureka server and client registry information.
     * </p>
     *
     * @param forceFullRegistryFetch Forces a full registry fetch. 是否强制获取一个完整的注册信息
     *
     * @return true if the registry was fetched
     */
    private boolean fetchRegistry(boolean forceFullRegistryFetch) {
        Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

        try {
            //获取本地缓存的注册列表信息
            // If the delta is disabled or if it is the first time, get all
            // applications
            Applications applications = getApplications();


            //shouldDisableDelta=true 禁用增量获取注册信息
            //forceFullRegistryFetch 方法传入参数，true标识使用全量拉取
            //
            if (clientConfig.shouldDisableDelta()
                    || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                    || forceFullRegistryFetch
                    || (applications == null)
                    || (applications.getRegisteredApplications().size() == 0)
                    || (applications.getVersion() == -1)) //Client application does not have latest library supporting delta
            {
                logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
                logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
                logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
                logger.info("Application is null : {}", (applications == null));
                logger.info("Registered Applications size is zero : {}",
                        (applications.getRegisteredApplications().size() == 0));
                logger.info("Application version is -1: {}", (applications.getVersion() == -1));
                // 执行 全量获取注册列表 并设置到本地缓存
                getAndStoreFullRegistry();
            } else {
                //执行增量获取注册列表 并刷新本地缓存
                getAndUpdateDelta(applications);
            }
            // 设置 应用集合 hashcode
            //计算应用集合 hashcode 。该变量用于校验增量获取的注册信息和 Eureka-Server 全量的注册信息是否一致( 完整 )
            applications.setAppsHashCode(applications.getReconcileHashCode());
            //记录打印实例总数
            logTotalInstances();
        } catch (Throwable e) {
            logger.error(PREFIX + "{} - was unable to refresh its cache! status = {}", appPathIdentifier, e.getMessage(), e);
            return false;
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }

        //触发缓存刷新监听事件
        // Notify about cache refresh before updating the instance remote status
        onCacheRefreshed();

        //更新当前应用本地缓存的远程状态
        // Update remote status based on refreshed data held in the cache
        updateInstanceRemoteStatus();

        // registry was fetched successfully, so return true
        return true;
    }

    /**
     * 更新当前应用本地缓存的（eureka-server端）远程状态
     * 即更新lastRemoteInstanceStatus字段
     */
    private synchronized void updateInstanceRemoteStatus() {
        // Determine this instance's status for this app and set to UNKNOWN if not found
        //eureka Server端保存的当前instanceInfo实例的状态
        InstanceInfo.InstanceStatus currentRemoteInstanceStatus = null;
        if (instanceInfo.getAppName() != null) {
            Application app = getApplication(instanceInfo.getAppName());
            if (app != null) {
                //获取eureka Server端保存的当前instanceInfo实例的状态
                InstanceInfo remoteInstanceInfo = app.getByInstanceId(instanceInfo.getId());
                if (remoteInstanceInfo != null) {
                    currentRemoteInstanceStatus = remoteInstanceInfo.getStatus();
                }
            }
        }
        //本地缓存中无当前instanceInfo实例的状态
        if (currentRemoteInstanceStatus == null) {
            currentRemoteInstanceStatus = InstanceInfo.InstanceStatus.UNKNOWN;
        }

        // 判断当前获取的远程状态与上次获取的远程状态是否一致
        // 对比本地缓存和最新的的当前应用实例在 Eureka-Server 的状态，若不同，
        // 更新本地缓存( 注意，只更新该缓存变量，不更新本地当前应用实例的状态( instanceInfo.status ) )
        // Notify if status changed
        if (lastRemoteInstanceStatus != currentRemoteInstanceStatus) {
            //不一致,触发远程状态改变监听事件
            onRemoteStatusChanged(lastRemoteInstanceStatus, currentRemoteInstanceStatus);
            //更新最新的远程状态到缓存变量上
            lastRemoteInstanceStatus = currentRemoteInstanceStatus;
        }
    }

    /**
     * @return Return he current instance status as seen on the Eureka server.
     */
    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return lastRemoteInstanceStatus;
    }

    private String getReconcileHashCode(Applications applications) {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        if (isFetchingRemoteRegionRegistries()) {
            for (Applications remoteApp : remoteRegionVsApps.values()) {
                remoteApp.populateInstanceCountMap(instanceCountMap);
            }
        }
        applications.populateInstanceCountMap(instanceCountMap);
        return Applications.getReconcileHashCode(instanceCountMap);
    }

    /**
     * 全量获取eureka-server端的注册列表，并写入本地缓存
     * Gets the full registry information from the eureka server and stores it locally.
     * When applying the full registry, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     * @return the full registry information.
     * @throws Throwable
     *             on error.
     */
    private void getAndStoreFullRegistry() throws Throwable {
        //获取当前的拉取注册表次数
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        logger.info("Getting all instance registry info from the eureka server");

        //全量注册表信息
        Applications apps = null;
        //向 eureka-server端发起请求获取全量信息
        // 参见 AbstractJerseyEurekaHttpClient.getApplications方法
        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        //判断响应返回状态
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            //成功后，读取返回的全量注册表信息
            apps = httpResponse.getEntity();
        }
        logger.info("The response status is {}", httpResponse.getStatusCode());

        if (apps == null) {
            logger.error("The application is null for some reason. Not storing this information");
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            //写入到本地缓存中
            localRegionApps.set(this.filterAndShuffle(apps));
            logger.debug("Got full registry with apps hashcode {}", apps.getAppsHashCode());
        } else {
            logger.warn("Not updating applications as another thread is updating it already");
        }
    }

    /**
     * 增量获取eureka-server端的注册列表信息
     * Get the delta registry information from the eureka server and update it locally.
     * When applying the delta, the following flow is observed:
     *
     * if (update generation have not advanced (due to another thread))
     *   atomically try to: update application with the delta and get reconcileHashCode
     *   abort entire processing otherwise
     *   do reconciliation if reconcileHashCode clash
     * fi
     *
     * @return the client response
     * @throws Throwable on error
     */
    private void getAndUpdateDelta(Applications applications) throws Throwable {
        long currentUpdateGeneration = fetchRegistryGeneration.get();

        Applications delta = null;
        EurekaHttpResponse<Applications> httpResponse = eurekaTransport.queryClient.getDelta(remoteRegionsRef.get());
        if (httpResponse.getStatusCode() == Status.OK.getStatusCode()) {
            delta = httpResponse.getEntity();
        }

        if (delta == null) {
            logger.warn("The server does not allow the delta revision to be applied because it is not safe. "
                    + "Hence got the full registry.");
            getAndStoreFullRegistry();
        } else if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            logger.debug("Got delta update with apps hashcode {}", delta.getAppsHashCode());
            String reconcileHashCode = "";
            if (fetchRegistryUpdateLock.tryLock()) {
                try {
                    updateDelta(delta);
                    reconcileHashCode = getReconcileHashCode(applications);
                } finally {
                    fetchRegistryUpdateLock.unlock();
                }
            } else {
                logger.warn("Cannot acquire update lock, aborting getAndUpdateDelta");
            }
            // There is a diff in number of instances for some reason
            if (!reconcileHashCode.equals(delta.getAppsHashCode()) || clientConfig.shouldLogDeltaDiff()) {
                reconcileAndLogDifference(delta, reconcileHashCode);  // this makes a remoteCall
            }
        } else {
            logger.warn("Not updating application delta as another thread is updating it already");
            logger.debug("Ignoring delta update with apps hashcode {}, as another thread is updating it already", delta.getAppsHashCode());
        }
    }

    /**
     * 记录本地存储的非过滤实例的总数。
     * Logs the total number of non-filtered instances stored locally.
     */
    private void logTotalInstances() {
        if (logger.isDebugEnabled()) {
            int totInstances = 0;
            for (Application application : getApplications().getRegisteredApplications()) {
                totInstances += application.getInstancesAsIsFromEureka().size();
            }
            logger.debug("The total number of all instances in the client now is {}", totInstances);
        }
    }

    /**
     * Reconcile the eureka server and client registry information and logs the differences if any.
     * When reconciling, the following flow is observed:
     *
     * make a remote call to the server for the full registry
     * calculate and log differences
     * if (update generation have not advanced (due to another thread))
     *   atomically set the registry to the new registry
     * fi
     *
     * @param delta
     *            the last delta registry information received from the eureka
     *            server.
     * @param reconcileHashCode
     *            the hashcode generated by the server for reconciliation.
     * @return ClientResponse the HTTP response object.
     * @throws Throwable
     *             on any error.
     */
    private void reconcileAndLogDifference(Applications delta, String reconcileHashCode) throws Throwable {
        logger.debug("The Reconcile hashcodes do not match, client : {}, server : {}. Getting the full registry",
                reconcileHashCode, delta.getAppsHashCode());

        RECONCILE_HASH_CODES_MISMATCH.increment();

        long currentUpdateGeneration = fetchRegistryGeneration.get();

        EurekaHttpResponse<Applications> httpResponse = clientConfig.getRegistryRefreshSingleVipAddress() == null
                ? eurekaTransport.queryClient.getApplications(remoteRegionsRef.get())
                : eurekaTransport.queryClient.getVip(clientConfig.getRegistryRefreshSingleVipAddress(), remoteRegionsRef.get());
        Applications serverApps = httpResponse.getEntity();

        if (serverApps == null) {
            logger.warn("Cannot fetch full registry from the server; reconciliation failure");
            return;
        }

        if (fetchRegistryGeneration.compareAndSet(currentUpdateGeneration, currentUpdateGeneration + 1)) {
            localRegionApps.set(this.filterAndShuffle(serverApps));
            getApplications().setVersion(delta.getVersion());
            logger.debug(
                    "The Reconcile hashcodes after complete sync up, client : {}, server : {}.",
                    getApplications().getReconcileHashCode(),
                    delta.getAppsHashCode());
        } else {
            logger.warn("Not setting the applications map as another thread has advanced the update generation");
        }
    }

    /**
     * Updates the delta information fetches from the eureka server into the
     * local cache.
     *
     * @param delta
     *            the delta information received from eureka server in the last
     *            poll cycle.
     */
    private void updateDelta(Applications delta) {
        int deltaCount = 0;
        for (Application app : delta.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                Applications applications = getApplications();
                String instanceRegion = instanceRegionChecker.getInstanceRegion(instance);
                if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                    Applications remoteApps = remoteRegionVsApps.get(instanceRegion);
                    if (null == remoteApps) {
                        remoteApps = new Applications();
                        remoteRegionVsApps.put(instanceRegion, remoteApps);
                    }
                    applications = remoteApps;
                }

                ++deltaCount;
                if (ActionType.ADDED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Added instance {} to the existing apps in region {}", instance.getId(), instanceRegion);
                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);
                } else if (ActionType.MODIFIED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Modified instance {} to the existing apps ", instance.getId());

                    applications.getRegisteredApplications(instance.getAppName()).addInstance(instance);

                } else if (ActionType.DELETED.equals(instance.getActionType())) {
                    Application existingApp = applications.getRegisteredApplications(instance.getAppName());
                    if (existingApp == null) {
                        applications.addApplication(app);
                    }
                    logger.debug("Deleted instance {} to the existing apps ", instance.getId());
                    applications.getRegisteredApplications(instance.getAppName()).removeInstance(instance);
                }
            }
        }
        logger.debug("The total number of instances fetched by the delta processor : {}", deltaCount);

        getApplications().setVersion(delta.getVersion());
        getApplications().shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());

        for (Applications applications : remoteRegionVsApps.values()) {
            applications.setVersion(delta.getVersion());
            applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
        }
    }

    /**
     * 初始化定时任务
     * Initializes all scheduled tasks.
     */
    private void initScheduledTasks() {
        //ScheduledExecutorService API
        //	schedule(Callable<V> callable, long delay, TimeUnit unit)  创建并执行在给定延迟后启用的 ScheduledFuture。
        //  schedule(Runnable command, long delay, TimeUnit unit)   创建并执行在给定延迟后启用的一次性操作
        //  scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
        //  创建并执行一个在给定初始延迟后首次启用的定期操作，后续操作具有给定的周期；也就是将在 initialDelay 后开始执行，然后在 initialDelay+period 后执行，接着在 initialDelay + 2 * period 后执行，依此类推。
        //  scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
        //  创建并执行一个在给定初始延迟后首次启用的定期操作，随后，在每一次执行终止和下一次执行开始之间都存在给定的延迟。

        //shouldFetchRegistry()是否从 Eureka-Server 拉取注册信息
        if (clientConfig.shouldFetchRegistry()) {
            //启动定时任务定时在Eureka-Server上拉取注册信息
            // registry cache refresh timer
            // getRegistryFetchIntervalSeconds()从 Eureka-Server 拉取注册信息频率，单位：秒
            int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
            //getCacheRefreshExecutorExponentialBackOffBound注册信息缓存刷新执行超时后的延迟重试的时间
            int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();

            //定时任务，延迟registryFetchIntervalSeconds 执行一次任务
            //具体周期行执行再TimedSupervisorTask中实现
            scheduler.schedule(
                    new TimedSupervisorTask(
                            "cacheRefresh",
                            scheduler,
                            cacheRefreshExecutor,
                            registryFetchIntervalSeconds,
                            TimeUnit.SECONDS,
                            expBackOffBound,
                            //拉取注册信息刷新本地缓存线程
                            new CacheRefreshThread()
                    ),
                    registryFetchIntervalSeconds, TimeUnit.SECONDS);
        }

        //shouldRegisterWithEureka()是否向 Eureka-Server 注册自身服务
        // 向 Eureka-Server 心跳（续租）执行器
        if (clientConfig.shouldRegisterWithEureka()) {
            //定时任务执行频率
            int renewalIntervalInSecs = instanceInfo.getLeaseInfo().getRenewalIntervalInSecs();
            //超时后的延迟重试的时间
            int expBackOffBound = clientConfig.getHeartbeatExecutorExponentialBackOffBound();
            logger.info("Starting heartbeat executor: " + "renew interval is: {}", renewalIntervalInSecs);

            // Heartbeat timer
            scheduler.schedule(
                    new TimedSupervisorTask(
                            "heartbeat",
                            scheduler,
                            heartbeatExecutor,
                            renewalIntervalInSecs,
                            TimeUnit.SECONDS,
                            expBackOffBound,
                            //心跳线程
                            new HeartbeatThread()
                    ),
                    renewalIntervalInSecs, TimeUnit.SECONDS);

            // InstanceInfo replicator
            instanceInfoReplicator = new InstanceInfoReplicator(
                    this,
                    instanceInfo,
                    clientConfig.getInstanceInfoReplicationIntervalSeconds(),
                    2); // burstSize

            //状态改变监听器，初始化
            statusChangeListener = new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "statusChangeListener";
                }

                @Override
                public void notify(StatusChangeEvent statusChangeEvent) {
                    if (InstanceStatus.DOWN == statusChangeEvent.getStatus() ||
                            InstanceStatus.DOWN == statusChangeEvent.getPreviousStatus()) {
                        // log at warn level if DOWN was involved
                        logger.warn("Saw local status change event {}", statusChangeEvent);
                    } else {
                        logger.info("Saw local status change event {}", statusChangeEvent);
                    }
                    instanceInfoReplicator.onDemandUpdate();
                }
            };

            //shouldOnDemandUpdateStatusChange()是否同步应用实例状态到 Eureka-Server
            if (clientConfig.shouldOnDemandUpdateStatusChange()) {
                //注册状态改变监听器到applicationInfoManager
                applicationInfoManager.registerStatusChangeListener(statusChangeListener);
            }

            instanceInfoReplicator.start(clientConfig.getInitialInstanceInfoReplicationIntervalSeconds());
        } else {
            logger.info("Not registering with Eureka server per configuration");
        }
    }

    /**
     * 取消关闭定时任务
     */
    private void cancelScheduledTasks() {
        if (instanceInfoReplicator != null) {
            instanceInfoReplicator.stop();
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (cacheRefreshExecutor != null) {
            cacheRefreshExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     *
     * @param instanceZone The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromDNS(clientConfig, instanceZone, preferSameZone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     */
    @Deprecated
    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return EndpointUtils.getDiscoveryServiceUrls(clientConfig, zone, urlRandomizer);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the list of EC2 URLs given the zone name.
     *
     * @param dnsName The dns name of the zone-specific CNAME
     * @param type CNAME or EIP that needs to be retrieved
     * @return The list of EC2 URLs associated with the dns name
     */
    @Deprecated
    public static Set<String> getEC2DiscoveryUrlsFromZone(String dnsName,
                                                          EndpointUtils.DiscoveryUrlType type) {
        return EndpointUtils.getEC2DiscoveryUrlsFromZone(dnsName, type);
    }

    /**
     * 刷新实例信息
     * Refresh the current local instanceInfo. Note that after a valid refresh where changes are observed, the
     * isDirty flag on the instanceInfo is set to true
     */
    void refreshInstanceInfo() {
        applicationInfoManager.refreshDataCenterInfoIfRequired();
        applicationInfoManager.refreshLeaseInfoIfRequired();

        InstanceStatus status;
        try {
            status = getHealthCheckHandler().getStatus(instanceInfo.getStatus());
        } catch (Exception e) {
            logger.warn("Exception from healthcheckHandler.getStatus, setting status to DOWN", e);
            status = InstanceStatus.DOWN;
        }

        if (null != status) {
            applicationInfoManager.setInstanceStatus(status);
        }
    }

    /**
     * 心跳线程
     * 执行 eureka client 定时向eureka server端发起续租请求
     * The heartbeat task that renews the lease in the given intervals.
     */
    private class HeartbeatThread implements Runnable {

        public void run() {
            if (renew()) {
                lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
            }
        }
    }

    @VisibleForTesting
    InstanceInfoReplicator getInstanceInfoReplicator() {
        return instanceInfoReplicator;
    }

    @VisibleForTesting
    InstanceInfo getInstanceInfo() {
        return instanceInfo;
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        if (healthCheckHandler == null) {
            if (null != healthCheckHandlerProvider) {
                healthCheckHandler = healthCheckHandlerProvider.get();
            } else if (null != healthCheckCallbackProvider) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(healthCheckCallbackProvider.get());
            }

            if (null == healthCheckHandler) {
                healthCheckHandler = new HealthCheckCallbackToHandlerBridge(null);
            }
        }

        return healthCheckHandler;
    }

    /**
     * The task that fetches the registry information at specified intervals.
     *
     */
    class CacheRefreshThread implements Runnable {
        public void run() {
            refreshRegistry();
        }
    }

    /**
     * 定时任务执行，定时从eureka Server 端拉取注册列表更新到本地缓存
     * 拉取注册信息列表刷新本地缓存
     */
    @VisibleForTesting
    void refreshRegistry() {
        try {
            boolean isFetchingRemoteRegionRegistries = isFetchingRemoteRegionRegistries();

            boolean remoteRegionsModified = false;
            // This makes sure that a dynamic change to remote regions to fetch is honored.
            String latestRemoteRegions = clientConfig.fetchRegistryForRemoteRegions();
            if (null != latestRemoteRegions) {
                String currentRemoteRegions = remoteRegionsToFetch.get();
                if (!latestRemoteRegions.equals(currentRemoteRegions)) {
                    // Both remoteRegionsToFetch and AzToRegionMapper.regionsToFetch need to be in sync
                    synchronized (instanceRegionChecker.getAzToRegionMapper()) {
                        if (remoteRegionsToFetch.compareAndSet(currentRemoteRegions, latestRemoteRegions)) {
                            String[] remoteRegions = latestRemoteRegions.split(",");
                            remoteRegionsRef.set(remoteRegions);
                            instanceRegionChecker.getAzToRegionMapper().setRegionsToFetch(remoteRegions);
                            remoteRegionsModified = true;
                        } else {
                            logger.info("Remote regions to fetch modified concurrently," +
                                    " ignoring change from {} to {}", currentRemoteRegions, latestRemoteRegions);
                        }
                    }
                } else {
                    // Just refresh mapping to reflect any DNS/Property change
                    instanceRegionChecker.getAzToRegionMapper().refreshMapping();
                }
            }

            //从eureka server端拉取注册列表，返回成功标识
            boolean success = fetchRegistry(remoteRegionsModified);
            if (success) {
                //成功后更新注册列表大小字段
                registrySize = localRegionApps.get().size();
                //成功后更新最后拉取注册表时间戳
                lastSuccessfulRegistryFetchTimestamp = System.currentTimeMillis();
            }

            //日志打印
            if (logger.isDebugEnabled()) {
                StringBuilder allAppsHashCodes = new StringBuilder();
                allAppsHashCodes.append("Local region apps hashcode: ");
                allAppsHashCodes.append(localRegionApps.get().getAppsHashCode());
                allAppsHashCodes.append(", is fetching remote regions? ");
                allAppsHashCodes.append(isFetchingRemoteRegionRegistries);
                for (Map.Entry<String, Applications> entry : remoteRegionVsApps.entrySet()) {
                    allAppsHashCodes.append(", Remote region: ");
                    allAppsHashCodes.append(entry.getKey());
                    allAppsHashCodes.append(" , apps hashcode: ");
                    allAppsHashCodes.append(entry.getValue().getAppsHashCode());
                }
                logger.debug("Completed cache refresh task for discovery. All Apps hash code is {} ",
                        allAppsHashCodes);
            }
        } catch (Throwable e) {
            logger.error("Cannot fetch registry from server", e);
        }        
    }
    
    /**
     * Fetch the registry information from back up registry if all eureka server
     * urls are unreachable.
     */
    private void fetchRegistryFromBackup() {
        try {
            @SuppressWarnings("deprecation")
            BackupRegistry backupRegistryInstance = newBackupRegistryInstance();
            if (null == backupRegistryInstance) { // backward compatibility with the old protected method, in case it is being used.
                backupRegistryInstance = backupRegistryProvider.get();
            }

            if (null != backupRegistryInstance) {
                Applications apps = null;
                if (isFetchingRemoteRegionRegistries()) {
                    String remoteRegionsStr = remoteRegionsToFetch.get();
                    if (null != remoteRegionsStr) {
                        apps = backupRegistryInstance.fetchRegistry(remoteRegionsStr.split(","));
                    }
                } else {
                    apps = backupRegistryInstance.fetchRegistry();
                }
                if (apps != null) {
                    final Applications applications = this.filterAndShuffle(apps);
                    applications.setAppsHashCode(applications.getReconcileHashCode());
                    localRegionApps.set(applications);
                    logTotalInstances();
                    logger.info("Fetched registry successfully from the backup");
                }
            } else {
                logger.warn("No backup registry instance defined & unable to find any discovery servers.");
            }
        } catch (Throwable e) {
            logger.warn("Cannot fetch applications from apps although backup registry was specified", e);
        }
    }

    /**
     * @deprecated Use injection to provide {@link BackupRegistry} implementation.
     */
    @Deprecated
    @Nullable
    protected BackupRegistry newBackupRegistryInstance()
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return null;
    }

    /**
     *
     * ，根据配置 eureka.shouldFilterOnlyUpInstances = true ( 默认值 ：true )
     * 过滤只保留状态为开启( UP )的应用实例，并随机打乱应用实例顺序。
     * 打乱后，实现调用应用服务的随机性。代码比较易懂，点击链接查看方法实现
     * Gets the <em>applications</em> after filtering the applications for
     * instances with only UP states and shuffling them.
     *
     * <p>
     * The filtering depends on the option specified by the configuration
     * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}. Shuffling helps
     * in randomizing the applications list there by avoiding the same instances
     * receiving traffic during start ups.
     * </p>
     *
     * @param apps
     *            The applications that needs to be filtered and shuffled.
     * @return The applications after the filter and the shuffle.
     */
    private Applications filterAndShuffle(Applications apps) {
        if (apps != null) {
            if (isFetchingRemoteRegionRegistries()) {
                Map<String, Applications> remoteRegionVsApps = new ConcurrentHashMap<String, Applications>();
                apps.shuffleAndIndexInstances(remoteRegionVsApps, clientConfig, instanceRegionChecker);
                for (Applications applications : remoteRegionVsApps.values()) {
                    applications.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
                }
                this.remoteRegionVsApps = remoteRegionVsApps;
            } else {
                apps.shuffleInstances(clientConfig.shouldFilterOnlyUpInstances());
            }
        }
        return apps;
    }

    private boolean isFetchingRemoteRegionRegistries() {
        return null != remoteRegionsToFetch.get();
    }

    /**
     * 触发eureka server中当前客户端的状态变更事件
     * Invoked when the remote status of this client has changed.
     * Subclasses may override this method to implement custom behavior if needed.
     *
     * @param oldStatus the previous remote {@link InstanceStatus}
     * @param newStatus the new remote {@link InstanceStatus}
     */
    protected void onRemoteStatusChanged(InstanceInfo.InstanceStatus oldStatus, InstanceInfo.InstanceStatus newStatus) {
        fireEvent(new StatusChangeEvent(oldStatus, newStatus));
    }


    /**
     * 触发缓存刷新事件
     * Invoked every time the local registry cache is refreshed (whether changes have
     * been detected or not).
     *
     * Subclasses may override this method to implement custom behavior if needed.
     */
    protected void onCacheRefreshed() {
        fireEvent(new CacheRefreshedEvent());
    }

    /**
     * 监听事件发送函数
     * Send the given event on the EventBus if one is available
     *
     * @param event the event to send on the eventBus
     */
    protected void fireEvent(final EurekaEvent event) {
        for (EurekaEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.info("Event {} throw an exception for listener {}", event, listener, e.getMessage());
            }
        }
    }


    /**
     * 获取时区zone信息
     * @deprecated see {@link com.netflix.appinfo.InstanceInfo#getZone(String[], com.netflix.appinfo.InstanceInfo)}
     *
     * Get the zone that a particular instance is in.
     *
     * @param myInfo
     *            - The InstanceInfo object of the instance.
     * @return - The zone in which the particular instance belongs to.
     */
    @Deprecated
    public static String getZone(InstanceInfo myInfo) {
        String[] availZones = staticClientConfig.getAvailabilityZones(staticClientConfig.getRegion());
        return InstanceInfo.getZone(availZones, myInfo);
    }

    /**
     * @deprecated see replacement in {@link com.netflix.discovery.endpoint.EndpointUtils}
     *
     * Get the region that this particular instance is in.
     *
     * @return - The region in which the particular instance belongs to.
     */
    @Deprecated
    public static String getRegion() {
        String region = staticClientConfig.getRegion();
        if (region == null) {
            region = "default";
        }
        region = region.trim().toLowerCase();
        return region;
    }

    /**
     * @deprecated use {@link #getServiceUrlsFromConfig(String, boolean)} instead.
     */
    @Deprecated
    public static List<String> getEurekaServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return EndpointUtils.getServiceUrlsFromConfig(staticClientConfig, instanceZone, preferSameZone);
    }

    public long getLastSuccessfulHeartbeatTimePeriod() {
        return lastSuccessfulHeartbeatTimestamp < 0
                ? lastSuccessfulHeartbeatTimestamp
                : System.currentTimeMillis() - lastSuccessfulHeartbeatTimestamp;
    }

    public long getLastSuccessfulRegistryFetchTimePeriod() {
        return lastSuccessfulRegistryFetchTimestamp < 0
                ? lastSuccessfulRegistryFetchTimestamp
                : System.currentTimeMillis() - lastSuccessfulRegistryFetchTimestamp;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRATION_PREFIX + "lastSuccessfulHeartbeatTimePeriod",
            description = "How much time has passed from last successful heartbeat", type = DataSourceType.GAUGE)
    private long getLastSuccessfulHeartbeatTimePeriodInternal() {
        long delay = getLastSuccessfulHeartbeatTimePeriod();
        heartbeatStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    // for metrics only
    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "lastSuccessfulRegistryFetchTimePeriod",
            description = "How much time has passed from last successful local registry update", type = DataSourceType.GAUGE)
    private long getLastSuccessfulRegistryFetchTimePeriodInternal() {
        long delay = getLastSuccessfulRegistryFetchTimePeriod();
        registryStalenessMonitor.update(computeStalenessMonitorDelay(delay));
        return delay;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "localRegistrySize",
            description = "Count of instances in the local registry", type = DataSourceType.GAUGE)
    public int localRegistrySize() {
        return registrySize;
    }


    private long computeStalenessMonitorDelay(long delay) {
        if (delay < 0) {
            return System.currentTimeMillis() - initTimestampMs;
        } else {
            return delay;
        }
    }

}
