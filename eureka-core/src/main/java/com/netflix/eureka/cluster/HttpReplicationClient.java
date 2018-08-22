package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.resources.ASGResource.ASGStatus;

/**
 * 当前节点与远程节点通讯的客户端接口
 * @author Tomasz Bak
 */
public interface HttpReplicationClient extends EurekaHttpClient {

    /**
     * 状态更新接口
     * @param asgName
     * @param newStatus
     * @return
     */
    EurekaHttpResponse<Void> statusUpdate(String asgName, ASGStatus newStatus);

    /**
     * 提交注册列表批量复制接口
     * @param replicationList
     * @return
     */
    EurekaHttpResponse<ReplicationListResponse> submitBatchUpdates(ReplicationList replicationList);

}
