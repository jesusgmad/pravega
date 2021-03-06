/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.storage.impl.rocksdb;

import io.pravega.segmentstore.storage.Cache;
import io.pravega.segmentstore.storage.CacheTestBase;
import com.google.common.io.Files;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;

/**
 * Unit tests for RocksDBCache.
 */
public class RocksDBCacheTests extends CacheTestBase {
    private final AtomicReference<File> tempDir = new AtomicReference<>();
    private final AtomicReference<RocksDBConfig> config = new AtomicReference<>();
    private final AtomicReference<RocksDBCacheFactory> factory = new AtomicReference<>();

    @Before
    public void setUp() {
        this.tempDir.set(Files.createTempDir());
        this.config.set(RocksDBConfig.builder().with(RocksDBConfig.DATABASE_DIR, tempDir.get().getAbsolutePath()).build());
        this.factory.set(new RocksDBCacheFactory(this.config.get()));
    }

    @After
    public void tearDown() {
        this.factory.getAndSet(null).close();
        this.tempDir.getAndSet(null).delete();
    }

    @Override
    protected Cache createCache(String cacheId) {
        return this.factory.get().getCache(cacheId);
    }
}
