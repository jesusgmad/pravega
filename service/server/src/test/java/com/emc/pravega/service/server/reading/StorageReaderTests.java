/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emc.pravega.service.server.reading;

import com.emc.pravega.service.contracts.SegmentProperties;
import com.emc.pravega.service.contracts.StreamSegmentNotExistsException;
import com.emc.pravega.service.server.CloseableExecutorService;
import com.emc.pravega.service.server.SegmentMetadata;
import com.emc.pravega.service.server.containers.StreamSegmentMetadata;
import com.emc.pravega.service.storage.ReadOnlyStorage;
import com.emc.pravega.service.storage.Storage;
import com.emc.pravega.service.storage.mocks.InMemoryStorage;
import com.emc.pravega.testcommon.AssertExtensions;
import com.emc.pravega.testcommon.IntentionalException;
import lombok.Cleanup;
import lombok.val;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Unit tests for the StorageReader class.
 */
public class StorageReaderTests {
    private static final int THREAD_POOL_SIZE = 50;
    private static final int MIN_SEGMENT_LENGTH = 101;
    private static final int MAX_SEGMENT_LENGTH = MIN_SEGMENT_LENGTH * 100;
    private static final SegmentMetadata SEGMENT_METADATA = new StreamSegmentMetadata("Segment1", 0, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * Tests the execute method with valid Requests:
     * * All StreamSegments exist and have enough data.
     * * All read offsets are valid (but we may choose to read more than the length of the Segment).
     * * ReadRequests may overlap.
     */
    @Test
    public void testValidRequests() throws Exception {
        final int defaultReadLength = MIN_SEGMENT_LENGTH - 1;
        final int offsetIncrement = defaultReadLength / 3;

        @Cleanup
        CloseableExecutorService executor = new CloseableExecutorService(Executors.newScheduledThreadPool(THREAD_POOL_SIZE));
        @Cleanup
        InMemoryStorage storage = new InMemoryStorage(executor.get());
        @Cleanup
        StorageReader reader = new StorageReader(SEGMENT_METADATA, storage, executor.get());

        byte[] segmentData = populateSegment(storage);
        HashMap<StorageReader.Request, CompletableFuture<StorageReader.Result>> requestCompletions = new HashMap<>();
        int readOffset = 0;
        while (readOffset < segmentData.length) {
            int readLength = Math.min(defaultReadLength, segmentData.length - readOffset);
            CompletableFuture<StorageReader.Result> requestCompletion = new CompletableFuture<>();
            StorageReader.Request r = new StorageReader.Request(readOffset, readLength, requestCompletion::complete, requestCompletion::completeExceptionally, TIMEOUT);
            reader.execute(r);
            requestCompletions.put(r, requestCompletion);
            readOffset += offsetIncrement;
        }

        // Check that the read requests returned with the right data.
        for (val entry : requestCompletions.entrySet()) {
            StorageReader.Result readData = entry.getValue().join();
            StorageReader.Request request = entry.getKey();
            int expectedReadLength = Math.min(request.getLength(), (int) (segmentData.length - request.getOffset()));

            Assert.assertNotNull("No data returned for request " + request, readData);
            Assert.assertEquals("Unexpected read length for request " + request, expectedReadLength, readData.getData().getLength());
            AssertExtensions.assertStreamEquals("Unexpected read contents for request " + request, new ByteArrayInputStream(segmentData, (int) request.getOffset(), expectedReadLength), readData.getData().getReader(), expectedReadLength);
        }
    }

    /**
     * Tests the execute method with invalid Requests.
     * * StreamSegment does not exist
     * * Invalid read offset
     * * Too long of a read (offset+length is beyond the Segment's length)
     */
    @Test
    public void testInvalidRequests() {
        @Cleanup
        CloseableExecutorService executor = new CloseableExecutorService(Executors.newScheduledThreadPool(THREAD_POOL_SIZE));
        @Cleanup
        InMemoryStorage storage = new InMemoryStorage(executor.get());
        @Cleanup
        StorageReader reader = new StorageReader(SEGMENT_METADATA, storage, executor.get());

        byte[] segmentData = populateSegment(storage);

        // Segment does not exist.
        AssertExtensions.assertThrows(
                "Request was not failed when StreamSegment does not exist.",
                () -> {
                    SegmentMetadata sm = new StreamSegmentMetadata("foo", 0, 0);
                    @Cleanup
                    StorageReader nonExistentReader = new StorageReader(sm, storage, executor.get());
                    sendRequest(nonExistentReader, 0, 1).join();
                },
                ex -> ex instanceof StreamSegmentNotExistsException);

        // Invalid read offset.
        AssertExtensions.assertThrows(
                "Request was not failed when bad offset was provided.",
                sendRequest(reader, segmentData.length + 1, 1)::join,
                ex -> ex instanceof ArrayIndexOutOfBoundsException);

        // Invalid read length.
        AssertExtensions.assertThrows(
                "Request was not failed when bad offset + length was provided.",
                sendRequest(reader, segmentData.length - 1, 2)::join,
                ex -> ex instanceof ArrayIndexOutOfBoundsException);
    }

    private CompletableFuture<StorageReader.Result> sendRequest(StorageReader reader, long offset, int length) {
        CompletableFuture<StorageReader.Result> requestCompletion = new CompletableFuture<>();
        reader.execute(new StorageReader.Request(offset, length, requestCompletion::complete, requestCompletion::completeExceptionally, TIMEOUT));
        return requestCompletion;
    }

    /**
     * Tests the ability to queue dependent reads (subsequent reads that only want to read a part of a previous read).
     * Test this both with successful and failed reads.
     */
    @Test
    public void testDependents() {
        @Cleanup
        CloseableExecutorService executor = new CloseableExecutorService(Executors.newScheduledThreadPool(THREAD_POOL_SIZE));
        TestStorage storage = new TestStorage();
        CompletableFuture<Integer> signal = new CompletableFuture<>();
        AtomicBoolean wasReadInvoked = new AtomicBoolean();
        storage.readImplementation = () -> {
            if (wasReadInvoked.getAndSet(true)) {
                Assert.fail("Read was invoked multiple times, which is a likely indicator that the requests were not chained.");
            }
            return signal;
        };

        @Cleanup
        StorageReader reader = new StorageReader(SEGMENT_METADATA, storage, executor.get());

        // Create some reads.
        CompletableFuture<StorageReader.Result> c1 = new CompletableFuture<>();
        CompletableFuture<StorageReader.Result> c2 = new CompletableFuture<>();
        reader.execute(new StorageReader.Request(0, 100, c1::complete, c1::completeExceptionally, TIMEOUT));
        reader.execute(new StorageReader.Request(50, 100, c2::complete, c2::completeExceptionally, TIMEOUT));

        Assert.assertFalse("One or more of the reads has completed prematurely.", c1.isDone() || c2.isDone());

        signal.completeExceptionally(new IntentionalException());
        Assert.assertTrue("The first read did not fail.", c1.isCompletedExceptionally());
        Assert.assertTrue("The second read did not fail.", c2.isCompletedExceptionally());
        AssertExtensions.assertThrows(
                "The first read was not failed with the correct exception.",
                c1::join,
                ex -> ex instanceof IntentionalException);

        AssertExtensions.assertThrows(
                "The second read was not failed with the correct exception.",
                c2::join,
                ex -> ex instanceof IntentionalException);
    }

    /**
     * Tests the ability to auto-cancel the requests when the StorageReader is closed.
     */
    @Test
    public void testAutoCancelRequests() {
        final int readCount = 100;
        @Cleanup
        CloseableExecutorService executor = new CloseableExecutorService(Executors.newScheduledThreadPool(THREAD_POOL_SIZE));
        TestStorage storage = new TestStorage();
        storage.readImplementation = CompletableFuture::new; // Just return a Future which we will never complete - simulates a high latency read.
        @Cleanup
        StorageReader reader = new StorageReader(SEGMENT_METADATA, storage, executor.get());

        // Create some reads.
        HashMap<StorageReader.Request, CompletableFuture<StorageReader.Result>> requestCompletions = new HashMap<>();

        for (int i = 0; i < readCount; i++) {
            CompletableFuture<StorageReader.Result> requestCompletion = new CompletableFuture<>();
            StorageReader.Request r = new StorageReader.Request(i * 10, 9, requestCompletion::complete, requestCompletion::completeExceptionally, TIMEOUT);
            reader.execute(r);
            requestCompletions.put(r, requestCompletion);
        }

        // Verify the reads aren't failed yet.
        for (val entry : requestCompletions.entrySet()) {
            Assert.assertFalse("Request is unexpectedly completed before close for request " + entry.getKey(), entry.getValue().isDone());
        }

        // Close the reader and verify the reads have all been cancelled.
        reader.close();
        for (val entry : requestCompletions.entrySet()) {
            Assert.assertTrue("Request is not completed with exception after close for request " + entry.getKey(), entry.getValue().isCompletedExceptionally());
            AssertExtensions.assertThrows(
                    "Request was not failed with a CancellationException after close for request " + entry.getKey(),
                    entry.getValue()::join,
                    ex -> ex instanceof CancellationException);
        }
    }

    private byte[] populateSegment(Storage storage) {
        Random random = new Random();
        int length = MIN_SEGMENT_LENGTH + random.nextInt(MAX_SEGMENT_LENGTH - MIN_SEGMENT_LENGTH);
        byte[] segmentData = new byte[length];
        random.nextBytes(segmentData);
        storage.create(SEGMENT_METADATA.getName(), TIMEOUT).join();
        storage.write(SEGMENT_METADATA.getName(), 0, new ByteArrayInputStream(segmentData), segmentData.length, TIMEOUT).join();
        return segmentData;
    }

    private class TestStorage implements ReadOnlyStorage {
        Supplier<CompletableFuture<Integer>> readImplementation;

        @Override
        public CompletableFuture<Integer> read(String streamSegmentName, long offset, byte[] buffer, int bufferOffset, int length, Duration timeout) {
            return this.readImplementation.get();
        }

        @Override
        public CompletableFuture<SegmentProperties> getStreamSegmentInfo(String streamSegmentName, Duration timeout) {
            // This method is not needed.
            return null;
        }

        @Override
        public CompletableFuture<Boolean> exists(String streamSegmentName, Duration timeout) {
            // This method is not needed.
            return null;
        }
    }
}