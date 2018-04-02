package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 响应缓存接口
 * @author David Liu
 */
public interface ResponseCache {

    /**
     * 过期缓存
     * @param appName
     * @param vipAddress
     * @param secureVipAddress
     */
    void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress);

    AtomicLong getVersionDelta();

    AtomicLong getVersionDeltaWithRegions();

    /**
     * 根据key获取响应数据
     * Get the cached information about applications.
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
     String get(Key key);

    /**
     * 根据key获取响应数据并压缩
     * Get the compressed information about the applications.
     *
     * @param key the key for which the compressed cached information needs to be obtained.
     * @return compressed payload which contains information about the applications.
     */
    byte[] getGZIP(Key key);
}
