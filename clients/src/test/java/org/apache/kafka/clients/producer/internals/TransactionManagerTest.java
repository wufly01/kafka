/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.MockClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TransactionalIdAuthorizationException;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AddOffsetsToTxnRequest;
import org.apache.kafka.common.requests.AddOffsetsToTxnResponse;
import org.apache.kafka.common.requests.AddPartitionsToTxnRequest;
import org.apache.kafka.common.requests.AddPartitionsToTxnResponse;
import org.apache.kafka.common.requests.EndTxnRequest;
import org.apache.kafka.common.requests.EndTxnResponse;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.FindCoordinatorRequest.CoordinatorType;
import org.apache.kafka.common.requests.FindCoordinatorResponse;
import org.apache.kafka.common.requests.InitProducerIdRequest;
import org.apache.kafka.common.requests.InitProducerIdResponse;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.TransactionResult;
import org.apache.kafka.common.requests.TxnOffsetCommitRequest;
import org.apache.kafka.common.requests.TxnOffsetCommitResponse;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.test.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TransactionManagerTest {
    private static final int MAX_REQUEST_SIZE = 1024 * 1024;
    private static final short ACKS_ALL = -1;
    private static final int MAX_RETRIES = Integer.MAX_VALUE;
    private static final String CLIENT_ID = "clientId";
    private static final int MAX_BLOCK_TIMEOUT = 1000;
    private static final int REQUEST_TIMEOUT = 1000;
    private final String transactionalId = "foobar";
    private final int transactionTimeoutMs = 1121;

    private TopicPartition tp0 = new TopicPartition("test", 0);
    private TopicPartition tp1 = new TopicPartition("test", 1);
    private MockTime time = new MockTime();
    private MockClient client = new MockClient(time);

    private Metadata metadata = new Metadata(0, Long.MAX_VALUE, true, new ClusterResourceListeners());
    private ApiVersions apiVersions = new ApiVersions();
    private Cluster cluster = TestUtils.singletonCluster("test", 2);
    private RecordAccumulator accumulator = null;
    private Sender sender = null;
    private TransactionManager transactionManager = null;
    private Node brokerNode = null;

    @Before
    public void setup() {
        Map<String, String> metricTags = new LinkedHashMap<>();
        metricTags.put("client-id", CLIENT_ID);
        int batchSize = 16 * 1024;
        MetricConfig metricConfig = new MetricConfig().tags(metricTags);
        this.brokerNode = new Node(0, "localhost", 2211);
        this.transactionManager = new TransactionManager(transactionalId, transactionTimeoutMs);
        Metrics metrics = new Metrics(metricConfig, time);
        this.accumulator = new RecordAccumulator(batchSize, 1024 * 1024, CompressionType.NONE, 0L, 0L, metrics, time, apiVersions, transactionManager);
        this.sender = new Sender(this.client, this.metadata, this.accumulator, true, MAX_REQUEST_SIZE, ACKS_ALL,
                MAX_RETRIES, metrics, this.time, REQUEST_TIMEOUT, 50, transactionManager, apiVersions);
        this.metadata.update(this.cluster, Collections.<String>emptySet(), time.milliseconds());
        client.setNode(brokerNode);
    }

    @Test(expected = IllegalStateException.class)
    public void testInvalidSequenceIncrement() {
        TransactionManager transactionManager = new TransactionManager();
        transactionManager.incrementSequenceNumber(tp0, 3333);
    }

    @Test
    public void testDefaultSequenceNumber() {
        TransactionManager transactionManager = new TransactionManager();
        assertEquals((int) transactionManager.sequenceNumber(tp0), 0);
        transactionManager.incrementSequenceNumber(tp0, 3);
        assertEquals((int) transactionManager.sequenceNumber(tp0), 3);
    }

    @Test
    public void testProducerIdReset() {
        TransactionManager transactionManager = new TransactionManager();
        assertEquals((int) transactionManager.sequenceNumber(tp0), 0);
        transactionManager.incrementSequenceNumber(tp0, 3);
        assertEquals((int) transactionManager.sequenceNumber(tp0), 3);
        transactionManager.resetProducerId();
        assertEquals((int) transactionManager.sequenceNumber(tp0), 0);
    }

    @Test
    public void testBasicTransaction() throws InterruptedException {
        // This is called from the initTransactions method in the producer as the first order of business.
        // It finds the coordinator and then gets a PID.
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);

        prepareProduceResponse(Errors.NONE, pid, epoch);
        assertFalse(transactionManager.transactionContainsPartition(tp0));
        sender.run(time.milliseconds());  // send addPartitions.
        // Check that only addPartitions was sent.
        assertTrue(transactionManager.transactionContainsPartition(tp0));
        assertFalse(responseFuture.isDone());

        sender.run(time.milliseconds());  // send produce request.
        assertTrue(responseFuture.isDone());

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(tp1, new OffsetAndMetadata(1));
        final String consumerGroupId = "myconsumergroup";
        TransactionalRequestResult addOffsetsResult = transactionManager.sendOffsetsToTransaction(offsets, consumerGroupId);

        assertFalse(transactionManager.hasPendingOffsetCommits());

        prepareAddOffsetsToTxnResponse(Errors.NONE, consumerGroupId, pid, epoch);

        sender.run(time.milliseconds());  // Send AddOffsetsRequest
        assertTrue(transactionManager.hasPendingOffsetCommits());  // We should now have created and queued the offset commit request.
        assertFalse(addOffsetsResult.isCompleted()); // the result doesn't complete until TxnOffsetCommit returns

        Map<TopicPartition, Errors> txnOffsetCommitResponse = new HashMap<>();
        txnOffsetCommitResponse.put(tp1, Errors.NONE);

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.GROUP, consumerGroupId);
        prepareTxnOffsetCommitResponse(consumerGroupId, pid, epoch, txnOffsetCommitResponse);

        assertEquals(null, transactionManager.coordinator(CoordinatorType.GROUP));
        sender.run(time.milliseconds());  // try to send TxnOffsetCommitRequest, but find we don't have a group coordinator.
        sender.run(time.milliseconds());  // send find coordinator for group request
        assertNotNull(transactionManager.coordinator(CoordinatorType.GROUP));
        assertTrue(transactionManager.hasPendingOffsetCommits());

        sender.run(time.milliseconds());  // send TxnOffsetCommitRequest commit.

        assertFalse(transactionManager.hasPendingOffsetCommits());
        assertTrue(addOffsetsResult.isCompleted());  // We should only be done after both RPCs complete.

        transactionManager.beginCommittingTransaction();
        prepareEndTxnResponse(Errors.NONE, TransactionResult.COMMIT, pid, epoch);
        sender.run(time.milliseconds());  // commit.

        assertFalse(transactionManager.isInTransaction());
        assertFalse(transactionManager.isCompletingTransaction());
        assertFalse(transactionManager.transactionContainsPartition(tp0));
    }

    @Test
    public void testDisconnectAndRetry() {
        // This is called from the initTransactions method in the producer as the first order of business.
        // It finds the coordinator and then gets a PID.
        transactionManager.initializeTransactions();
        prepareFindCoordinatorResponse(Errors.NONE, true, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator, connection lost.

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator
        sender.run(time.milliseconds());
        assertEquals(brokerNode, transactionManager.coordinator(CoordinatorType.TRANSACTION));
    }

    @Test
    public void testCoordinatorLost() {
        // This is called from the initTransactions method in the producer as the first order of business.
        // It finds the coordinator and then gets a PID.
        final long pid = 13131L;
        final short epoch = 1;
        TransactionalRequestResult initPidResult = transactionManager.initializeTransactions();
        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator
        sender.run(time.milliseconds());
        assertEquals(brokerNode, transactionManager.coordinator(CoordinatorType.TRANSACTION));

        prepareInitPidResponse(Errors.NOT_COORDINATOR, false, pid, epoch);
        sender.run(time.milliseconds());  // send pid, get not coordinator. Should resend the FindCoordinator and InitPid requests

        assertEquals(null, transactionManager.coordinator(CoordinatorType.TRANSACTION));
        assertFalse(initPidResult.isCompleted());
        assertFalse(transactionManager.hasProducerId());

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());
        assertEquals(brokerNode, transactionManager.coordinator(CoordinatorType.TRANSACTION));
        assertFalse(initPidResult.isCompleted());
        prepareInitPidResponse(Errors.NONE, false, pid, epoch);
        sender.run(time.milliseconds());  // get pid and epoch

        assertTrue(initPidResult.isCompleted()); // The future should only return after the second round of retries succeed.
        assertTrue(transactionManager.hasProducerId());
        assertEquals(pid, transactionManager.producerIdAndEpoch().producerId);
        assertEquals(epoch, transactionManager.producerIdAndEpoch().epoch);
    }

    @Test
    public void testTransactionalIdAuthorizationFailureInFindCoordinator() {
        TransactionalRequestResult initPidResult = transactionManager.initializeTransactions();
        prepareFindCoordinatorResponse(Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED, false,
                CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator
        sender.run(time.milliseconds());

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof TransactionalIdAuthorizationException);

        sender.run(time.milliseconds()); // one more run to fail the InitProducerId future
        assertTrue(initPidResult.isCompleted());
        assertFalse(initPidResult.isSuccessful());
        assertTrue(initPidResult.error() instanceof TransactionalIdAuthorizationException);

        assertFatalError(TransactionalIdAuthorizationException.class);
    }

    @Test
    public void testTransactionalIdAuthorizationFailureInInitProducerId() {
        final long pid = 13131L;
        TransactionalRequestResult initPidResult = transactionManager.initializeTransactions();
        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator
        sender.run(time.milliseconds());
        assertEquals(brokerNode, transactionManager.coordinator(CoordinatorType.TRANSACTION));

        prepareInitPidResponse(Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED, false, pid, RecordBatch.NO_PRODUCER_EPOCH);
        sender.run(time.milliseconds());

        assertTrue(transactionManager.hasError());
        assertTrue(initPidResult.isCompleted());
        assertFalse(initPidResult.isSuccessful());
        assertTrue(initPidResult.error() instanceof TransactionalIdAuthorizationException);

        assertFatalError(TransactionalIdAuthorizationException.class);
    }

    @Test
    public void testGroupAuthorizationFailureInFindCoordinator() {
        final String consumerGroupId = "consumer";
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        TransactionalRequestResult sendOffsetsResult = transactionManager.sendOffsetsToTransaction(
                singletonMap(new TopicPartition("foo", 0), new OffsetAndMetadata(39L)), consumerGroupId);

        prepareAddOffsetsToTxnResponse(Errors.NONE, consumerGroupId, pid, epoch);
        sender.run(time.milliseconds());  // AddOffsetsToTxn Handled, TxnOffsetCommit Enqueued
        sender.run(time.milliseconds());  // FindCoordinator Enqueued

        prepareFindCoordinatorResponse(Errors.GROUP_AUTHORIZATION_FAILED, false, CoordinatorType.GROUP, consumerGroupId);
        sender.run(time.milliseconds());  // FindCoordinator Failed
        sender.run(time.milliseconds());  // TxnOffsetCommit Aborted
        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof GroupAuthorizationException);
        assertTrue(sendOffsetsResult.isCompleted());
        assertFalse(sendOffsetsResult.isSuccessful());
        assertTrue(sendOffsetsResult.error() instanceof GroupAuthorizationException);

        GroupAuthorizationException exception = (GroupAuthorizationException) sendOffsetsResult.error();
        assertEquals(consumerGroupId, exception.groupId());

        assertAbortableError(GroupAuthorizationException.class);
    }

    @Test
    public void testGroupAuthorizationFailureInTxnOffsetCommit() {
        final String consumerGroupId = "consumer";
        final long pid = 13131L;
        final short epoch = 1;
        final TopicPartition tp = new TopicPartition("foo", 0);

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        TransactionalRequestResult sendOffsetsResult = transactionManager.sendOffsetsToTransaction(
                singletonMap(tp, new OffsetAndMetadata(39L)), consumerGroupId);

        prepareAddOffsetsToTxnResponse(Errors.NONE, consumerGroupId, pid, epoch);
        sender.run(time.milliseconds());  // AddOffsetsToTxn Handled, TxnOffsetCommit Enqueued
        sender.run(time.milliseconds());  // FindCoordinator Enqueued

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.GROUP, consumerGroupId);
        sender.run(time.milliseconds());  // FindCoordinator Returned

        prepareTxnOffsetCommitResponse(consumerGroupId, pid, epoch, singletonMap(tp, Errors.GROUP_AUTHORIZATION_FAILED));
        sender.run(time.milliseconds());  // TxnOffsetCommit Handled

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof GroupAuthorizationException);
        assertTrue(sendOffsetsResult.isCompleted());
        assertFalse(sendOffsetsResult.isSuccessful());
        assertTrue(sendOffsetsResult.error() instanceof GroupAuthorizationException);

        GroupAuthorizationException exception = (GroupAuthorizationException) sendOffsetsResult.error();
        assertEquals(consumerGroupId, exception.groupId());

        assertAbortableError(GroupAuthorizationException.class);
    }

    @Test
    public void testTransactionalIdAuthorizationFailureInAddOffsetsToTxn() {
        final String consumerGroupId = "consumer";
        final long pid = 13131L;
        final short epoch = 1;
        final TopicPartition tp = new TopicPartition("foo", 0);

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        TransactionalRequestResult sendOffsetsResult = transactionManager.sendOffsetsToTransaction(
                singletonMap(tp, new OffsetAndMetadata(39L)), consumerGroupId);

        prepareAddOffsetsToTxnResponse(Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED, consumerGroupId, pid, epoch);
        sender.run(time.milliseconds());  // AddOffsetsToTxn Handled

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof TransactionalIdAuthorizationException);
        assertTrue(sendOffsetsResult.isCompleted());
        assertFalse(sendOffsetsResult.isSuccessful());
        assertTrue(sendOffsetsResult.error() instanceof TransactionalIdAuthorizationException);

        assertFatalError(TransactionalIdAuthorizationException.class);
    }

    @Test
    public void testTransactionalIdAuthorizationFailureInTxnOffsetCommit() {
        final String consumerGroupId = "consumer";
        final long pid = 13131L;
        final short epoch = 1;
        final TopicPartition tp = new TopicPartition("foo", 0);

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        TransactionalRequestResult sendOffsetsResult = transactionManager.sendOffsetsToTransaction(
                singletonMap(tp, new OffsetAndMetadata(39L)), consumerGroupId);

        prepareAddOffsetsToTxnResponse(Errors.NONE, consumerGroupId, pid, epoch);
        sender.run(time.milliseconds());  // AddOffsetsToTxn Handled, TxnOffsetCommit Enqueued
        sender.run(time.milliseconds());  // FindCoordinator Enqueued

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.GROUP, consumerGroupId);
        sender.run(time.milliseconds());  // FindCoordinator Returned

        prepareTxnOffsetCommitResponse(consumerGroupId, pid, epoch, singletonMap(tp, Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED));
        sender.run(time.milliseconds());  // TxnOffsetCommit Handled

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof TransactionalIdAuthorizationException);
        assertTrue(sendOffsetsResult.isCompleted());
        assertFalse(sendOffsetsResult.isSuccessful());
        assertTrue(sendOffsetsResult.error() instanceof TransactionalIdAuthorizationException);

        assertFatalError(TransactionalIdAuthorizationException.class);
    }

    @Test
    public void testTopicAuthorizationFailureInAddPartitions() {
        final long pid = 13131L;
        final short epoch = 1;
        final TopicPartition tp = new TopicPartition("foo", 0);

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp);

        prepareAddPartitionsToTxn(tp, Errors.TOPIC_AUTHORIZATION_FAILED);
        sender.run(time.milliseconds());

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof TopicAuthorizationException);

        TopicAuthorizationException exception = (TopicAuthorizationException) transactionManager.lastError();
        assertEquals(singleton(tp.topic()), exception.unauthorizedTopics());

        assertAbortableError(TopicAuthorizationException.class);
    }

    @Test
    public void testTransactionalIdAuthorizationFailureInAddPartitions() {
        final long pid = 13131L;
        final short epoch = 1;
        final TopicPartition tp = new TopicPartition("foo", 0);

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp);

        prepareAddPartitionsToTxn(tp, Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED);
        sender.run(time.milliseconds());

        assertTrue(transactionManager.hasError());
        assertTrue(transactionManager.lastError() instanceof TransactionalIdAuthorizationException);

        assertFatalError(TransactionalIdAuthorizationException.class);
    }

    @Test
    public void testFlushPendingPartitionsOnCommit() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());

        TransactionalRequestResult commitResult = transactionManager.beginCommittingTransaction();

        // we have an append, an add partitions request, and now also an endtxn.
        // The order should be:
        //  1. Add Partitions
        //  2. Produce
        //  3. EndTxn.
        assertFalse(transactionManager.transactionContainsPartition(tp0));
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);

        sender.run(time.milliseconds());  // AddPartitions.
        assertTrue(transactionManager.transactionContainsPartition(tp0));
        assertFalse(responseFuture.isDone());
        assertFalse(commitResult.isCompleted());

        prepareProduceResponse(Errors.NONE, pid, epoch);
        sender.run(time.milliseconds());  // Produce.
        assertTrue(responseFuture.isDone());

        prepareEndTxnResponse(Errors.NONE, TransactionResult.COMMIT, pid, epoch);
        assertFalse(commitResult.isCompleted());
        assertTrue(transactionManager.isInTransaction());
        assertTrue(transactionManager.isCompletingTransaction());

        sender.run(time.milliseconds());
        assertTrue(commitResult.isCompleted());
        assertFalse(transactionManager.isInTransaction());
    }

    @Test
    public void testMultipleAddPartitionsPerForOneProduce() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        // User does one producer.sed
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);

        assertFalse(transactionManager.transactionContainsPartition(tp0));

        // Sender flushes one add partitions. The produce goes next.
        sender.run(time.milliseconds());  // send addPartitions.
        // Check that only addPartitions was sent.
        assertTrue(transactionManager.transactionContainsPartition(tp0));

        // In the mean time, the user does a second produce to a different partition
        transactionManager.maybeAddPartitionToTransaction(tp1);
        Future<RecordMetadata> secondResponseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        prepareAddPartitionsToTxnResponse(Errors.NONE, tp1, epoch, pid);
        prepareProduceResponse(Errors.NONE, pid, epoch);

        assertFalse(transactionManager.transactionContainsPartition(tp1));

        assertFalse(responseFuture.isDone());
        assertFalse(secondResponseFuture.isDone());

        // The second add partitions should go out here.
        sender.run(time.milliseconds());  // send second add partitions request
        assertTrue(transactionManager.transactionContainsPartition(tp1));

        assertFalse(responseFuture.isDone());
        assertFalse(secondResponseFuture.isDone());

        // Finally we get to the produce.
        sender.run(time.milliseconds());  // send produce request

        assertTrue(responseFuture.isDone());
        assertTrue(secondResponseFuture.isDone());
    }

    @Test(expected = ExecutionException.class)
    public void testProducerFencedException() throws InterruptedException, ExecutionException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);
        prepareProduceResponse(Errors.INVALID_PRODUCER_EPOCH, pid, epoch);
        sender.run(time.milliseconds()); // Add partitions.

        sender.run(time.milliseconds());  // send produce.

        assertTrue(responseFuture.isDone());
        assertTrue(transactionManager.hasError());
        responseFuture.get();
    }

    @Test
    public void testDisallowCommitOnProduceFailure() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        TransactionalRequestResult commitResult = transactionManager.beginCommittingTransaction();
        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);
        prepareProduceResponse(Errors.OUT_OF_ORDER_SEQUENCE_NUMBER, pid, epoch);

        sender.run(time.milliseconds());  // Send AddPartitionsRequest
        assertFalse(commitResult.isCompleted());
        sender.run(time.milliseconds());  // Send Produce Request, returns OutOfOrderSequenceException.

        sender.run(time.milliseconds());  // try to commit.
        assertTrue(commitResult.isCompleted());  // commit should be cancelled with exception without being sent.

        try {
            commitResult.await();
            fail();  // the get() must throw an exception.
        } catch (KafkaException e) {
        }

        try {
            responseFuture.get();
            fail("Expected produce future to raise an exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof OutOfOrderSequenceException);
        }

        // Commit is not allowed, so let's abort and try again.
        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();
        prepareEndTxnResponse(Errors.NONE, TransactionResult.ABORT, pid, epoch);
        sender.run(time.milliseconds());  // Send abort request. It is valid to transition from ERROR to ABORT

        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
        assertTrue(transactionManager.isReady());  // make sure we are ready for a transaction now.
    }

    @Test
    public void testAllowAbortOnProduceFailure() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);
        prepareProduceResponse(Errors.OUT_OF_ORDER_SEQUENCE_NUMBER, pid, epoch);
        prepareEndTxnResponse(Errors.NONE, TransactionResult.ABORT, pid, epoch);

        sender.run(time.milliseconds());  // Send AddPartitionsRequest
        sender.run(time.milliseconds());  // Send Produce Request, returns OutOfOrderSequenceException.

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();
        sender.run(time.milliseconds());  // try to abort
        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
        assertTrue(transactionManager.isReady());  // make sure we are ready for a transaction now.
    }

    @Test
    public void testCancelUnsentAddPartitionsAndProduceOnAbort() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();
        // note since no partitions were added to the transaction, no EndTxn will be sent

        sender.run(time.milliseconds());  // try to abort
        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
        assertTrue(transactionManager.isReady());  // make sure we are ready for a transaction now.

        try {
            responseFuture.get();
            fail("Expected produce future to raise an exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof KafkaException);
        }
    }

    @Test
    public void testAbortResendsAddPartitionErrorIfRetried() throws InterruptedException {
        final long producerId = 13131L;
        final short producerEpoch = 1;

        doInitTransactions(producerId, producerEpoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);
        prepareAddPartitionsToTxnResponse(Errors.UNKNOWN_TOPIC_OR_PARTITION, tp0, producerEpoch, producerId);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        sender.run(time.milliseconds());  // Send AddPartitions and let it fail
        assertFalse(responseFuture.isDone());

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();

        // we should resend the AddPartitions
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, producerEpoch, producerId);
        prepareEndTxnResponse(Errors.NONE, TransactionResult.ABORT, producerId, producerEpoch);

        sender.run(time.milliseconds());  // Resend AddPartitions
        sender.run(time.milliseconds());  // Send EndTxn

        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
        assertTrue(transactionManager.isReady());  // make sure we are ready for a transaction now.

        try {
            responseFuture.get();
            fail("Expected produce future to raise an exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof KafkaException);
        }
    }

    @Test
    public void testAbortResendsProduceRequestIfRetried() throws Exception {
        final long producerId = 13131L;
        final short producerEpoch = 1;

        doInitTransactions(producerId, producerEpoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);
        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, producerEpoch, producerId);
        prepareProduceResponse(Errors.REQUEST_TIMED_OUT, producerId, producerEpoch);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        sender.run(time.milliseconds());  // Send AddPartitions
        sender.run(time.milliseconds());  // Send ProduceRequest and let it fail

        assertFalse(responseFuture.isDone());

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();

        // we should resend the ProduceRequest before aborting
        prepareProduceResponse(Errors.NONE, producerId, producerEpoch);
        prepareEndTxnResponse(Errors.NONE, TransactionResult.ABORT, producerId, producerEpoch);

        sender.run(time.milliseconds());  // Resend ProduceRequest
        sender.run(time.milliseconds());  // Send EndTxn

        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
        assertTrue(transactionManager.isReady());  // make sure we are ready for a transaction now.

        RecordMetadata recordMetadata = responseFuture.get();
        assertEquals(tp0.topic(), recordMetadata.topic());
    }

    @Test
    public void testHandlingOfUnknownTopicPartitionErrorOnAddPartitions() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;

        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxnResponse(Errors.UNKNOWN_TOPIC_OR_PARTITION, tp0, epoch, pid);

        sender.run(time.milliseconds());  // Send AddPartitionsRequest
        assertFalse(transactionManager.transactionContainsPartition(tp0));  // The partition should not yet be added.

        prepareAddPartitionsToTxnResponse(Errors.NONE, tp0, epoch, pid);
        prepareProduceResponse(Errors.NONE, pid, epoch);
        sender.run(time.milliseconds());  // Send AddPartitionsRequest successfully.
        assertTrue(transactionManager.transactionContainsPartition(tp0));

        sender.run(time.milliseconds());  // Send ProduceRequest.
        assertTrue(responseFuture.isDone());
    }

    @Test
    public void testHandlingOfUnknownTopicPartitionErrorOnTxnOffsetCommit() throws InterruptedException {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(tp1, new OffsetAndMetadata(1));
        final String consumerGroupId = "myconsumergroup";

        TransactionalRequestResult addOffsetsResult = transactionManager.sendOffsetsToTransaction(offsets, consumerGroupId);
        prepareAddOffsetsToTxnResponse(Errors.NONE, consumerGroupId, pid, epoch);

        sender.run(time.milliseconds());  // send AddOffsetsToTxnResult

        assertFalse(addOffsetsResult.isCompleted());  // The request should complete only after the TxnOffsetCommit completes.

        Map<TopicPartition, Errors> txnOffsetCommitResponse = new HashMap<>();
        txnOffsetCommitResponse.put(tp1, Errors.UNKNOWN_TOPIC_OR_PARTITION);

        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.GROUP, consumerGroupId);
        prepareTxnOffsetCommitResponse(consumerGroupId, pid, epoch, txnOffsetCommitResponse);

        assertEquals(null, transactionManager.coordinator(CoordinatorType.GROUP));
        sender.run(time.milliseconds());  // try to send TxnOffsetCommitRequest, but find we don't have a group coordinator.
        sender.run(time.milliseconds());  // send find coordinator for group request
        assertNotNull(transactionManager.coordinator(CoordinatorType.GROUP));
        assertTrue(transactionManager.hasPendingOffsetCommits());

        sender.run(time.milliseconds());  // send TxnOffsetCommitRequest request.

        assertTrue(transactionManager.hasPendingOffsetCommits());  // The TxnOffsetCommit failed.
        assertFalse(addOffsetsResult.isCompleted());  // We should only be done after both RPCs complete successfully.

        txnOffsetCommitResponse.put(tp1, Errors.NONE);
        prepareTxnOffsetCommitResponse(consumerGroupId, pid, epoch, txnOffsetCommitResponse);
        sender.run(time.milliseconds());  // Send TxnOffsetCommitRequest again.

        assertTrue(addOffsetsResult.isCompleted());
        assertTrue(addOffsetsResult.isSuccessful());
    }

    @Test
    public void shouldNotAddPartitionsToTransactionWhenTopicAuthorizationFailed() throws Exception {
        verifyAddPartitionsFailsWithPartitionLevelError(Errors.TOPIC_AUTHORIZATION_FAILED);
    }

    @Test
    public void shouldNotSendAbortTxnRequestWhenOnlyAddPartitionsRequestFailed() throws Exception {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        prepareAddPartitionsToTxnResponse(Errors.TOPIC_AUTHORIZATION_FAILED, tp0, epoch, pid);
        sender.run(time.milliseconds());  // Send AddPartitionsRequest

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();
        assertFalse(abortResult.isCompleted());

        sender.run(time.milliseconds());
        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
    }

    @Test
    public void shouldNotSendAbortTxnRequestWhenOnlyAddOffsetsRequestFailed() throws Exception {
        final long pid = 13131L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(tp1, new OffsetAndMetadata(1));
        final String consumerGroupId = "myconsumergroup";

        transactionManager.sendOffsetsToTransaction(offsets, consumerGroupId);

        TransactionalRequestResult abortResult = transactionManager.beginAbortingTransaction();

        prepareAddOffsetsToTxnResponse(Errors.TOPIC_AUTHORIZATION_FAILED, consumerGroupId, pid, epoch);
        sender.run(time.milliseconds());  // Send AddOffsetsToTxnRequest
        assertFalse(abortResult.isCompleted());

        sender.run(time.milliseconds());
        assertTrue(abortResult.isCompleted());
        assertTrue(abortResult.isSuccessful());
    }

    private void verifyAddPartitionsFailsWithPartitionLevelError(final Errors error) throws InterruptedException {
        final long pid = 1L;
        final short epoch = 1;

        doInitTransactions(pid, epoch);

        transactionManager.beginTransaction();
        transactionManager.maybeAddPartitionToTransaction(tp0);

        Future<RecordMetadata> responseFuture = accumulator.append(tp0, time.milliseconds(), "key".getBytes(),
                "value".getBytes(), Record.EMPTY_HEADERS, null, MAX_BLOCK_TIMEOUT).future;
        assertFalse(responseFuture.isDone());
        prepareAddPartitionsToTxn(tp0, error);
        sender.run(time.milliseconds());  // attempt send addPartitions.
        assertTrue(transactionManager.hasError());
        assertFalse(transactionManager.transactionContainsPartition(tp0));
    }

    private void prepareAddPartitionsToTxn(final TopicPartition tp, final Errors error) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                return body instanceof AddPartitionsToTxnRequest &&
                        ((AddPartitionsToTxnRequest) body).partitions().contains(tp);
            }
        }, new AddPartitionsToTxnResponse(0, singletonMap(tp, error)));
    }

    private void prepareFindCoordinatorResponse(Errors error, boolean shouldDisconnect,
                                                final CoordinatorType coordinatorType,
                                                final String coordinatorKey) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                FindCoordinatorRequest findCoordinatorRequest = (FindCoordinatorRequest) body;
                assertEquals(findCoordinatorRequest.coordinatorType(), coordinatorType);
                assertEquals(findCoordinatorRequest.coordinatorKey(), coordinatorKey);
                return true;
            }
        }, new FindCoordinatorResponse(error, brokerNode), shouldDisconnect);
    }

    private void prepareInitPidResponse(Errors error, boolean shouldDisconnect, long pid, short epoch) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                InitProducerIdRequest initProducerIdRequest = (InitProducerIdRequest) body;
                assertEquals(initProducerIdRequest.transactionalId(), transactionalId);
                assertEquals(initProducerIdRequest.transactionTimeoutMs(), transactionTimeoutMs);
                return true;
            }
        }, new InitProducerIdResponse(0, error, pid, epoch), shouldDisconnect);
    }

    private void prepareProduceResponse(Errors error, final long pid, final short epoch) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                ProduceRequest produceRequest = (ProduceRequest) body;
                MemoryRecords records = produceRequest.partitionRecordsOrFail().get(tp0);
                assertNotNull(records);
                Iterator<MutableRecordBatch> batchIterator = records.batches().iterator();
                assertTrue(batchIterator.hasNext());
                MutableRecordBatch batch = batchIterator.next();
                assertFalse(batchIterator.hasNext());
                assertTrue(batch.isTransactional());
                assertEquals(pid, batch.producerId());
                assertEquals(epoch, batch.producerEpoch());
                assertEquals(transactionalId, produceRequest.transactionalId());
                return true;
            }
        }, produceResponse(tp0, 0, error, 0));

    }

    private void prepareAddPartitionsToTxnResponse(Errors error, final TopicPartition topicPartition, final short epoch, final long pid) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                AddPartitionsToTxnRequest addPartitionsToTxnRequest = (AddPartitionsToTxnRequest) body;
                assertEquals(pid, addPartitionsToTxnRequest.producerId());
                assertEquals(epoch, addPartitionsToTxnRequest.producerEpoch());
                assertEquals(singletonList(topicPartition), addPartitionsToTxnRequest.partitions());
                assertEquals(transactionalId, addPartitionsToTxnRequest.transactionalId());
                return true;
            }
        }, new AddPartitionsToTxnResponse(0, singletonMap(topicPartition, error)));
    }

    private void prepareEndTxnResponse(Errors error, final TransactionResult result, final long pid, final short epoch) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                EndTxnRequest endTxnRequest = (EndTxnRequest) body;
                assertEquals(transactionalId, endTxnRequest.transactionalId());
                assertEquals(pid, endTxnRequest.producerId());
                assertEquals(epoch, endTxnRequest.producerEpoch());
                assertEquals(result, endTxnRequest.command());
                return true;
            }
        }, new EndTxnResponse(0, error));
    }

    private void prepareAddOffsetsToTxnResponse(Errors error, final String consumerGroupId, final long producerId,
                                                final short producerEpoch) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                AddOffsetsToTxnRequest addOffsetsToTxnRequest = (AddOffsetsToTxnRequest) body;
                assertEquals(consumerGroupId, addOffsetsToTxnRequest.consumerGroupId());
                assertEquals(transactionalId, addOffsetsToTxnRequest.transactionalId());
                assertEquals(producerId, addOffsetsToTxnRequest.producerId());
                assertEquals(producerEpoch, addOffsetsToTxnRequest.producerEpoch());
                return true;
            }
        }, new AddOffsetsToTxnResponse(0, error));
    }

    private void prepareTxnOffsetCommitResponse(final String consumerGroupId, final long producerId,
                                                final short producerEpoch, Map<TopicPartition, Errors> txnOffsetCommitResponse) {
        client.prepareResponse(new MockClient.RequestMatcher() {
            @Override
            public boolean matches(AbstractRequest body) {
                TxnOffsetCommitRequest txnOffsetCommitRequest = (TxnOffsetCommitRequest) body;
                assertEquals(consumerGroupId, txnOffsetCommitRequest.consumerGroupId());
                assertEquals(producerId, txnOffsetCommitRequest.producerId());
                assertEquals(producerEpoch, txnOffsetCommitRequest.producerEpoch());
                return true;
            }
        }, new TxnOffsetCommitResponse(0, txnOffsetCommitResponse));

    }

    private ProduceResponse produceResponse(TopicPartition tp, long offset, Errors error, int throttleTimeMs) {
        ProduceResponse.PartitionResponse resp = new ProduceResponse.PartitionResponse(error, offset, RecordBatch.NO_TIMESTAMP);
        Map<TopicPartition, ProduceResponse.PartitionResponse> partResp = singletonMap(tp, resp);
        return new ProduceResponse(partResp, throttleTimeMs);
    }

    private void doInitTransactions(long pid, short epoch) {
        transactionManager.initializeTransactions();
        prepareFindCoordinatorResponse(Errors.NONE, false, CoordinatorType.TRANSACTION, transactionalId);
        sender.run(time.milliseconds());  // find coordinator
        sender.run(time.milliseconds());
        assertEquals(brokerNode, transactionManager.coordinator(CoordinatorType.TRANSACTION));

        prepareInitPidResponse(Errors.NONE, false, pid, epoch);
        sender.run(time.milliseconds());  // get pid.
        assertTrue(transactionManager.hasProducerId());
    }

    private void assertAbortableError(Class<? extends RuntimeException> cause) {
        try {
            transactionManager.beginTransaction();
            fail("Should have raised " + cause.getSimpleName());
        } catch (KafkaException e) {
            assertTrue(cause.isAssignableFrom(e.getCause().getClass()));
            assertTrue(transactionManager.hasError());
        }

        assertTrue(transactionManager.hasError());
        transactionManager.beginAbortingTransaction();
        assertFalse(transactionManager.hasError());
    }

    private void assertFatalError(Class<? extends RuntimeException> cause) {
        assertTrue(transactionManager.hasError());

        try {
            transactionManager.beginAbortingTransaction();
            fail("Should have raised " + cause.getSimpleName());
        } catch (KafkaException e) {
            assertTrue(cause.isAssignableFrom(e.getCause().getClass()));
            assertTrue(transactionManager.hasError());
        }

        // Transaction abort cannot clear fatal error state
        try {
            transactionManager.beginAbortingTransaction();
            fail("Should have raised " + cause.getSimpleName());
        } catch (KafkaException e) {
            assertTrue(cause.isAssignableFrom(e.getCause().getClass()));
            assertTrue(transactionManager.hasError());
        }
    }
}
