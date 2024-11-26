/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.server.replica.fetcher;

import com.alibaba.fluss.cluster.ServerNode;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.metadata.TableBucket;
import com.alibaba.fluss.record.FileLogRecords;
import com.alibaba.fluss.record.LogRecords;
import com.alibaba.fluss.record.MemoryLogRecords;
import com.alibaba.fluss.rpc.entity.FetchLogResultForBucket;
import com.alibaba.fluss.rpc.messages.FetchLogRequest;
import com.alibaba.fluss.server.entity.FetchData;
import com.alibaba.fluss.server.log.FetchParams;
import com.alibaba.fluss.server.replica.Replica;
import com.alibaba.fluss.server.replica.ReplicaManager;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.fluss.server.utils.RpcMessageUtils.getFetchLogData;
import static com.alibaba.fluss.utils.function.ThrowingRunnable.unchecked;

/** The leader end point used for test, which replica manager in local. */
public class TestingLeaderEndpoint implements LeaderEndpoint {

    private final ReplicaManager replicaManager;
    private final ServerNode localNode;
    /** The max size for the fetch response. */
    private final int maxFetchSize;
    /** The max fetch size for a bucket in bytes. */
    private final int maxFetchSizeForBucket;

    public TestingLeaderEndpoint(
            Configuration conf, ReplicaManager replicaManager, ServerNode localNode) {
        this.replicaManager = replicaManager;
        this.localNode = localNode;
        this.maxFetchSize = (int) conf.get(ConfigOptions.LOG_FETCH_MAX_BYTES).getBytes();
        this.maxFetchSizeForBucket =
                (int) conf.get(ConfigOptions.LOG_FETCH_MAX_BYTES_FOR_BUCKET).getBytes();
    }

    @Override
    public ServerNode leaderNode() {
        return localNode;
    }

    @Override
    public CompletableFuture<Long> fetchLocalLogEndOffset(TableBucket tableBucket) {
        Replica replica = replicaManager.getReplicaOrException(tableBucket);
        return CompletableFuture.completedFuture(replica.getLocalLogEndOffset());
    }

    @Override
    public CompletableFuture<Long> fetchLocalLogStartOffset(TableBucket tableBucket) {
        Replica replica = replicaManager.getReplicaOrException(tableBucket);
        return CompletableFuture.completedFuture(replica.getLocalLogStartOffset());
    }

    @Override
    public CompletableFuture<Map<TableBucket, FetchLogResultForBucket>> fetchLog(
            FetchLogRequest fetchLogRequest) {
        CompletableFuture<Map<TableBucket, FetchLogResultForBucket>> response =
                new CompletableFuture<>();
        Map<TableBucket, FetchData> fetchLogData = getFetchLogData(fetchLogRequest);
        replicaManager.fetchLogRecords(
                new FetchParams(
                        fetchLogRequest.getFollowerServerId(), fetchLogRequest.getMaxBytes()),
                fetchLogData,
                result -> response.complete(processResult(result)));
        return response;
    }

    @Override
    public Optional<FetchLogRequest> buildFetchLogRequest(
            Map<TableBucket, BucketFetchStatus> replicas) {
        return RemoteLeaderEndpoint.buildFetchLogRequest(
                replicas, localNode.id(), maxFetchSize, maxFetchSizeForBucket);
    }

    @Override
    public void close() {
        // nothing to do now.
    }

    /** Convert FileLogRecords to MemoryLogRecords. */
    private Map<TableBucket, FetchLogResultForBucket> processResult(
            Map<TableBucket, FetchLogResultForBucket> fetchDataMap) {
        Map<TableBucket, FetchLogResultForBucket> result = new HashMap<>();
        fetchDataMap.forEach(
                (tb, value) -> {
                    LogRecords logRecords = value.recordsOrEmpty();
                    if (logRecords instanceof FileLogRecords) {
                        FileLogRecords fileRecords = (FileLogRecords) logRecords;
                        // convert FileLogRecords to MemoryLogRecords
                        ByteBuffer buffer = ByteBuffer.allocate(fileRecords.sizeInBytes());
                        unchecked(() -> fileRecords.readInto(buffer, 0)).run();
                        MemoryLogRecords memRecords = MemoryLogRecords.pointToByteBuffer(buffer);
                        result.put(
                                tb,
                                new FetchLogResultForBucket(
                                        tb, memRecords, value.getHighWatermark()));
                    } else {
                        result.put(tb, value);
                    }
                });

        return result;
    }
}