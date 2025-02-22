/*
 * Tencent is pleased to support the open source community by making
 * Firestorm-Spark remote shuffle server available. 
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.rss.server.buffer;

import com.google.common.collect.RangeMap;
import com.google.common.io.Files;
import com.tencent.rss.common.ShuffleDataResult;
import com.tencent.rss.common.ShufflePartitionedData;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.server.ShuffleFlushManager;
import com.tencent.rss.server.ShuffleServer;
import com.tencent.rss.server.ShuffleServerConf;
import com.tencent.rss.server.ShuffleServerMetrics;
import com.tencent.rss.server.StatusCode;
import com.tencent.rss.server.storage.StorageManager;
import com.tencent.rss.server.storage.StorageManagerFactory;
import com.tencent.rss.storage.util.StorageType;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ShuffleBufferManagerTest extends BufferTestBase {
  private ShuffleBufferManager shuffleBufferManager;
  private ShuffleFlushManager mockShuffleFlushManager;
  private ShuffleServerConf conf;

  @Before
  public void setUp() {
    conf = new ShuffleServerConf();
    File tmpDir = Files.createTempDir();
    File dataDir = new File(tmpDir, "data");
    conf.setString(ShuffleServerConf.RSS_STORAGE_TYPE, StorageType.LOCALFILE.name());
    conf.setString(ShuffleServerConf.RSS_STORAGE_BASE_PATH, dataDir.getAbsolutePath());
    conf.set(ShuffleServerConf.SERVER_BUFFER_CAPACITY, 500L);
    conf.set(ShuffleServerConf.SERVER_MEMORY_SHUFFLE_LOWWATERMARK_PERCENTAGE, 20.0);
    conf.set(ShuffleServerConf.SERVER_MEMORY_SHUFFLE_HIGHWATERMARK_PERCENTAGE, 80.0);
    conf.setLong(ShuffleServerConf.DISK_CAPACITY, 1024L * 1024L * 1024L);
    mockShuffleFlushManager = mock(ShuffleFlushManager.class);
    shuffleBufferManager = new ShuffleBufferManager(conf, mockShuffleFlushManager);
  }

  @Test
  public void registerBufferTest() {
    String appId = "registerBufferTest";
    int shuffleId = 1;

    StatusCode sc = shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 1);
    assertEquals(StatusCode.SUCCESS, sc);
    sc = shuffleBufferManager.registerBuffer(appId, shuffleId, 2, 3);
    assertEquals(StatusCode.SUCCESS, sc);

    Map<String, Map<Integer, RangeMap<Integer, ShuffleBuffer>>> bufferPool = shuffleBufferManager.getBufferPool();

    assertNotNull(bufferPool.get(appId).get(shuffleId).get(0));
    ShuffleBuffer buffer = bufferPool.get(appId).get(shuffleId).get(0);
    assertEquals(buffer, bufferPool.get(appId).get(shuffleId).get(1));
    assertNotNull(bufferPool.get(appId).get(shuffleId).get(2));
    assertEquals(bufferPool.get(appId).get(shuffleId).get(2), bufferPool.get(appId).get(shuffleId).get(3));

    // register again
    shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 1);
    assertEquals(buffer, bufferPool.get(appId).get(shuffleId).get(0));
  }

  @Test
  public void getShuffleDataTest() {
    String appId = "getShuffleDataTest";
    shuffleBufferManager.registerBuffer(appId, 1, 0, 1);
    shuffleBufferManager.registerBuffer(appId, 2, 0, 1);
    shuffleBufferManager.registerBuffer(appId, 3, 0, 1);
    shuffleBufferManager.registerBuffer(appId, 4, 0, 1);
    ShufflePartitionedData spd1 = createData(0, 68);
    ShufflePartitionedData spd2 = createData(0, 68);
    ShufflePartitionedData spd3 = createData(0, 68);
    ShufflePartitionedData spd4 = createData(0, 68);
    shuffleBufferManager.cacheShuffleData(appId, 1, false, spd1);
    shuffleBufferManager.cacheShuffleData(appId, 2, false, spd2);
    shuffleBufferManager.cacheShuffleData(appId, 2, false, spd3);
    shuffleBufferManager.cacheShuffleData(appId, 3, false, spd4);
    // validate buffer, no flush happened
    Map<String, Map<Integer, RangeMap<Integer, ShuffleBuffer>>> bufferPool =
        shuffleBufferManager.getBufferPool();
    assertEquals(100, bufferPool.get(appId).get(1).get(0).getSize());
    assertEquals(200, bufferPool.get(appId).get(2).get(0).getSize());
    assertEquals(100, bufferPool.get(appId).get(3).get(0).getSize());
    // validate get shuffle data
    ShuffleDataResult sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, Constants.INVALID_BLOCK_ID, 60);
    assertArrayEquals(spd2.getBlockList()[0].getData(), sdr.getData());
    long lastBlockId = spd2.getBlockList()[0].getBlockId();
    sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, lastBlockId, 100);
    assertArrayEquals(spd3.getBlockList()[0].getData(), sdr.getData());
    // flush happen
    ShufflePartitionedData spd5 = createData(0, 10);
    shuffleBufferManager.cacheShuffleData(appId, 4, false, spd5);
    // according to flush strategy, some buffers should be moved to inFlushMap
    assertEquals(0, bufferPool.get(appId).get(1).get(0).getBlocks().size());
    assertEquals(1, bufferPool.get(appId).get(1).get(0).getInFlushBlockMap().size());
    assertEquals(0, bufferPool.get(appId).get(2).get(0).getBlocks().size());
    assertEquals(1, bufferPool.get(appId).get(2).get(0).getInFlushBlockMap().size());
    assertEquals(0, bufferPool.get(appId).get(3).get(0).getBlocks().size());
    assertEquals(1, bufferPool.get(appId).get(3).get(0).getInFlushBlockMap().size());
    // keep buffer whose size < low water mark
    assertEquals(1, bufferPool.get(appId).get(4).get(0).getBlocks().size());
    // data in flush buffer now, it also can be got before flush finish
    sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, Constants.INVALID_BLOCK_ID, 60);
    assertArrayEquals(spd2.getBlockList()[0].getData(), sdr.getData());
    lastBlockId = spd2.getBlockList()[0].getBlockId();
    sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, lastBlockId, 100);
    assertArrayEquals(spd3.getBlockList()[0].getData(), sdr.getData());
    // cache data again, it should cause flush
    spd1 = createData(0, 10);
    shuffleBufferManager.cacheShuffleData(appId, 1, false, spd1);
    assertEquals(1, bufferPool.get(appId).get(1).get(0).getBlocks().size());
    // finish flush
    bufferPool.get(appId).get(1).get(0).getInFlushBlockMap().clear();
    bufferPool.get(appId).get(2).get(0).getInFlushBlockMap().clear();
    bufferPool.get(appId).get(3).get(0).getInFlushBlockMap().clear();
    // empty data return
    sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, Constants.INVALID_BLOCK_ID, 60);
    assertEquals(0, sdr.getData().length);
    lastBlockId = spd2.getBlockList()[0].getBlockId();
    sdr = shuffleBufferManager.getShuffleData(
        appId, 2, 0, lastBlockId, 100);
    assertEquals(0, sdr.getData().length);
  }

  @Test
  public void shuffleIdToSizeTest() {
    String appId1 = "shuffleIdToSizeTest1";
    String appId2 = "shuffleIdToSizeTest2";
    shuffleBufferManager.registerBuffer(appId1, 1, 0, 0);
    shuffleBufferManager.registerBuffer(appId1, 2, 0, 0);
    shuffleBufferManager.registerBuffer(appId2, 1, 0, 0);
    shuffleBufferManager.registerBuffer(appId2, 2, 0, 0);
    ShufflePartitionedData spd1 = createData(0, 67);
    ShufflePartitionedData spd2 = createData(0, 68);
    ShufflePartitionedData spd3 = createData(0, 68);
    ShufflePartitionedData spd4 = createData(0, 68);
    ShufflePartitionedData spd5 = createData(0, 68);
    shuffleBufferManager.cacheShuffleData(appId1, 1, false, spd1);
    shuffleBufferManager.cacheShuffleData(appId1, 2, false, spd2);
    shuffleBufferManager.cacheShuffleData(appId1, 2, false, spd3);
    shuffleBufferManager.cacheShuffleData(appId2, 1, false, spd4);

    // validate metadata of shuffle size
    Map<String, Map<Integer, AtomicLong>> shuffleSizeMap = shuffleBufferManager.getShuffleSizeMap();
    assertEquals(99, shuffleSizeMap.get(appId1).get(1).get());
    assertEquals(200, shuffleSizeMap.get(appId1).get(2).get());
    assertEquals(100, shuffleSizeMap.get(appId2).get(1).get());

    shuffleBufferManager.cacheShuffleData(appId2, 2, false, spd5);
    // flush happen
    assertEquals(99, shuffleSizeMap.get(appId1).get(1).get());
    assertEquals(0, shuffleSizeMap.get(appId1).get(2).get());
    assertEquals(0, shuffleSizeMap.get(appId2).get(1).get());
    assertEquals(0, shuffleSizeMap.get(appId2).get(1).get());
    shuffleBufferManager.releaseMemory(400, true, false);

    ShufflePartitionedData spd6 = createData(0, 300);
    shuffleBufferManager.cacheShuffleData(appId1, 1, false, spd6);
    // flush happen
    assertEquals(0, shuffleSizeMap.get(appId1).get(1).get());
    shuffleBufferManager.releaseMemory(463, true, false);

    shuffleBufferManager.cacheShuffleData(appId1, 1, false, spd1);
    shuffleBufferManager.cacheShuffleData(appId1, 2, false, spd2);
    shuffleBufferManager.cacheShuffleData(appId2, 1, false, spd4);
    shuffleBufferManager.removeBuffer(appId1);
    assertNull(shuffleSizeMap.get(appId1));
    assertEquals(100, shuffleSizeMap.get(appId2).get(1).get());
  }

  @Test
  public void cacheShuffleDataTest() {
    String appId = "cacheShuffleDataTest";
    int shuffleId = 1;

    int startPartitionNum = (int) ShuffleServerMetrics.gaugeTotalPartitionNum.get();
    StatusCode sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(StatusCode.NO_REGISTER, sc);
    shuffleBufferManager.registerBuffer(appId, shuffleId + 1, 0, 1);
    assertEquals(startPartitionNum + 1, (int) ShuffleServerMetrics.gaugeTotalPartitionNum.get());
    sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(StatusCode.NO_REGISTER, sc);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 100, 101);
    assertEquals(startPartitionNum + 2, (int) ShuffleServerMetrics.gaugeTotalPartitionNum.get());
    sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(StatusCode.NO_REGISTER, sc);

    shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 1);
    assertEquals(startPartitionNum + 3, (int) ShuffleServerMetrics.gaugeTotalPartitionNum.get());
    sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(StatusCode.SUCCESS, sc);

    Map<String, Map<Integer, RangeMap<Integer, ShuffleBuffer>>> bufferPool = shuffleBufferManager.getBufferPool();
    ShuffleBuffer buffer = bufferPool.get(appId).get(shuffleId).get(0);
    assertEquals(48, buffer.getSize());
    assertEquals(48, shuffleBufferManager.getUsedMemory());

    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(96, buffer.getSize());
    assertEquals(96, shuffleBufferManager.getUsedMemory());

    // reach high water lever, flush
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 273));
    assertEquals(0, buffer.getSize());
    assertEquals(401, shuffleBufferManager.getUsedMemory());
    assertEquals(401, shuffleBufferManager.getInFlushSize());
    verify(mockShuffleFlushManager, times(1)).addToFlushQueue(any());

    // now buffer should be full
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 100));
    verify(mockShuffleFlushManager, times(1)).addToFlushQueue(any());
    sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 1));
    assertEquals(StatusCode.NO_BUFFER, sc);

    // size won't be reduce which should be processed by flushManager, reset buffer size to 0
    shuffleBufferManager.resetSize();
    shuffleBufferManager.removeBuffer(appId);
    assertEquals(startPartitionNum, (int) ShuffleServerMetrics.gaugeTotalPartitionNum.get());
    shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 0);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 1, 1);
    shuffleBufferManager.registerBuffer(appId, 2, 0, 0);

    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 200));
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(1, 200));
    shuffleBufferManager.cacheShuffleData(appId, 2, false, createData(0, 32));
    ShuffleBuffer buffer0 = bufferPool.get(appId).get(shuffleId).get(0);
    ShuffleBuffer buffer1 = bufferPool.get(appId).get(shuffleId).get(1);
    ShuffleBuffer buffer2 = bufferPool.get(appId).get(2).get(0);
    assertEquals(0, buffer0.getSize());
    assertEquals(0, buffer1.getSize());
    assertEquals(64, buffer2.getSize());
    assertEquals(528, shuffleBufferManager.getUsedMemory());
    assertEquals(464, shuffleBufferManager.getInFlushSize());
    verify(mockShuffleFlushManager, times(3)).addToFlushQueue(any());
  }

  @Test
  public void cacheShuffleDataWithPreAllocationTest() {
    String appId = "cacheShuffleDataWithPreAllocationTest";
    int shuffleId = 1;

    shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 1);
    // pre allocate memory
    shuffleBufferManager.requireMemory(48, true);
    assertEquals(48, shuffleBufferManager.getUsedMemory());
    assertEquals(48, shuffleBufferManager.getPreAllocatedSize());
    // receive data with preAllocation
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, true, createData(0, 16));
    assertEquals(48, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getPreAllocatedSize());
    // release memory
    shuffleBufferManager.releaseMemory(48, false, false);
    assertEquals(0, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getPreAllocatedSize());
    // receive data without preAllocation
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 17));
    assertEquals(49, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getPreAllocatedSize());
    // release memory
    shuffleBufferManager.releaseMemory(49, false, false);
    assertEquals(0, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getPreAllocatedSize());

    // release memory with preAllocation
    shuffleBufferManager.requireMemory(16, true);
    shuffleBufferManager.releaseMemory(16, false, true);
    assertEquals(0, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getPreAllocatedSize());

    // pre allocate all memory
    shuffleBufferManager.requireMemory(500, true);
    assertEquals(500, shuffleBufferManager.getUsedMemory());
    assertEquals(500, shuffleBufferManager.getPreAllocatedSize());

    // no buffer if data without pre allocation
    StatusCode sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(1, 16));
    assertEquals(StatusCode.NO_BUFFER, sc);

    // actual data size < spillThreshold, won't flush
    sc = shuffleBufferManager.cacheShuffleData(appId, shuffleId, true, createData(1, 16));
    assertEquals(StatusCode.SUCCESS, sc);
    assertEquals(500, shuffleBufferManager.getUsedMemory());
    assertEquals(452, shuffleBufferManager.getPreAllocatedSize());

    // actual data size > highWaterMark, flush
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, true, createData(0, 400));
    assertEquals(StatusCode.SUCCESS, sc);
    assertEquals(500, shuffleBufferManager.getUsedMemory());
    assertEquals(20, shuffleBufferManager.getPreAllocatedSize());
    verify(mockShuffleFlushManager, times(1)).addToFlushQueue(any());
  }

  @Test
  public void bufferSizeTest() throws Exception {
    ShuffleServer mockShuffleServer = mock(ShuffleServer.class);
    StorageManager storageManager = StorageManagerFactory.getInstance().createStorageManager("serverId", conf);
    ShuffleFlushManager shuffleFlushManager = new ShuffleFlushManager(conf, "serverId", mockShuffleServer, storageManager);
    shuffleBufferManager = new ShuffleBufferManager(conf, shuffleFlushManager);

    when(mockShuffleServer
        .getShuffleFlushManager())
        .thenReturn(shuffleFlushManager);
    when(mockShuffleServer
        .getShuffleBufferManager())
        .thenReturn(shuffleBufferManager);

    String appId = "bufferSizeTest";
    int shuffleId = 1;

    shuffleBufferManager.registerBuffer(appId, shuffleId, 0, 1);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 2, 3);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 4, 5);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 6, 7);
    shuffleBufferManager.registerBuffer(appId, shuffleId, 8, 9);
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(48, shuffleBufferManager.getUsedMemory());

    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 16));
    assertEquals(96, shuffleBufferManager.getUsedMemory());

    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 300));
    waitForFlush(shuffleFlushManager, appId, shuffleId, 3);
    assertEquals(0, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getInFlushSize());

    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 64));
    assertEquals(96, shuffleBufferManager.getUsedMemory());
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(2, 64));
    assertEquals(192, shuffleBufferManager.getUsedMemory());
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(4, 64));
    assertEquals(288, shuffleBufferManager.getUsedMemory());
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(6, 64));
    assertEquals(384, shuffleBufferManager.getUsedMemory());
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(8, 64));
    waitForFlush(shuffleFlushManager, appId, shuffleId, 5);
    assertEquals(0, shuffleBufferManager.getUsedMemory());
    assertEquals(0, shuffleBufferManager.getInFlushSize());

    shuffleBufferManager.registerBuffer("bufferSizeTest1", shuffleId, 0, 1);
    shuffleBufferManager.cacheShuffleData(appId, shuffleId, false, createData(0, 32));
    assertEquals(64, shuffleBufferManager.getUsedMemory());
    shuffleBufferManager.cacheShuffleData("bufferSizeTest1", shuffleId, false, createData(0, 32));
    assertEquals(128, shuffleBufferManager.getUsedMemory());
    assertEquals(2, shuffleBufferManager.getBufferPool().keySet().size());
    shuffleBufferManager.removeBuffer(appId);
    assertEquals(64, shuffleBufferManager.getUsedMemory());
    assertEquals(1, shuffleBufferManager.getBufferPool().keySet().size());
  }

  private void waitForFlush(ShuffleFlushManager shuffleFlushManager,
      String appId, int shuffleId, int expectedBlockNum) throws Exception {
    int retry = 0;
    long committedCount = 0;
    do {
      committedCount = shuffleFlushManager.getCommittedBlockIds(appId, shuffleId).getLongCardinality();
      if (committedCount < expectedBlockNum) {
        Thread.sleep(500);
      }
      retry++;
      if (retry > 10) {
        fail("Flush data time out");
      }
    } while (committedCount < expectedBlockNum);
  }
}
