/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.hdfs.trident;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.storm.Config;
import org.apache.storm.hdfs.trident.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.trident.format.FileNameFormat;
import org.apache.storm.hdfs.trident.format.RecordFormat;
import org.apache.storm.hdfs.trident.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.trident.rotation.FileSizeRotationPolicy;
import org.apache.storm.trident.tuple.TridentTuple;
import org.apache.storm.tuple.Fields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class HdfsStateTest {

    private static final String TEST_OUT_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "trident-unit-test").toString();

    private static final String FILE_NAME_PREFIX = "hdfs-data-";
    private static final String TEST_TOPOLOGY_NAME = "test-topology";
    private static final String INDEX_FILE_PREFIX = ".index.";
    private final TestFileNameFormat fileNameFormat = new TestFileNameFormat();

    private HdfsState createHdfsState() {

        Fields hdfsFields = new Fields("f1");

        RecordFormat recordFormat = new DelimitedRecordFormat().withFields(hdfsFields);

        FileRotationPolicy rotationPolicy = new FileSizeRotationPolicy(5.0f, FileSizeRotationPolicy.Units.MB);

        HdfsState.Options options = new HdfsState.HdfsFileOptions()
            .withFileNameFormat(fileNameFormat)
            .withRecordFormat(recordFormat)
            .withRotationPolicy(rotationPolicy)
            .withFsUrl("file://" + TEST_OUT_DIR);

        Map<String, Object> conf = new HashMap<>();
        conf.put(Config.TOPOLOGY_NAME, TEST_TOPOLOGY_NAME);

        HdfsState state = new HdfsState(options);
        state.prepare(conf, null, 0, 1);
        return state;
    }

    private List<TridentTuple> createMockTridentTuples(int count) {
        TridentTuple tuple = mock(TridentTuple.class);
        when(tuple.getValueByField(anyString())).thenReturn("data");
        List<TridentTuple> tuples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tuples.add(tuple);
        }
        return tuples;
    }

    private List<String> getLinesFromCurrentDataFile() throws IOException {
        Path dataFile = Paths.get(TEST_OUT_DIR, fileNameFormat.getCurrentFileName());
        return Files.readAllLines(dataFile, Charset.defaultCharset());
    }

    @BeforeEach
    public void setUp() {
        FileUtils.deleteQuietly(new File(TEST_OUT_DIR));
    }

    @Test
    public void testPrepare() {
        HdfsState state = createHdfsState();
        Collection<File> files = FileUtils.listFiles(new File(TEST_OUT_DIR), null, false);
        File hdfsDataFile = Paths.get(TEST_OUT_DIR, FILE_NAME_PREFIX + "0").toFile();
        assertTrue(files.contains(hdfsDataFile));
    }

    @Test
    public void testIndexFileCreation() {
        HdfsState state = createHdfsState();
        state.beginCommit(1L);
        Collection<File> files = FileUtils.listFiles(new File(TEST_OUT_DIR), null, false);
        File hdfsIndexFile = Paths.get(TEST_OUT_DIR, INDEX_FILE_PREFIX + TEST_TOPOLOGY_NAME + ".0").toFile();
        assertTrue(files.contains(hdfsIndexFile));
    }

    @Test
    public void testUpdateState() throws Exception {
        HdfsState state = createHdfsState();
        state.beginCommit(1L);
        int tupleCount = 100;
        state.updateState(createMockTridentTuples(tupleCount), null);
        state.commit(1L);
        state.close();
        List<String> lines = getLinesFromCurrentDataFile();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < tupleCount; i++) {
            expected.add("data");
        }
        assertEquals(tupleCount, lines.size());
        assertEquals(expected, lines);
    }

    @Test
    public void testRecoverOneBatch() throws Exception {
        HdfsState state = createHdfsState();
        // batch 1 is played with 25 tuples initially.
        state.beginCommit(1L);
        state.updateState(createMockTridentTuples(25), null);
        // batch 1 is replayed with 50 tuples.
        int replayBatchSize = 50;
        state.beginCommit(1L);
        state.updateState(createMockTridentTuples(replayBatchSize), null);
        state.commit(1L);
        // close the state to force flush
        state.close();
        // Ensure that the original batch1 is discarded and new one is persisted.
        List<String> lines = getLinesFromCurrentDataFile();
        assertEquals(replayBatchSize, lines.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < replayBatchSize; i++) {
            expected.add("data");
        }
        assertEquals(expected, lines);
    }

    @Test
    public void testRecoverMultipleBatches() throws Exception {
        HdfsState state = createHdfsState();

        // batch 1
        int batch1Count = 10;
        state.beginCommit(1L);
        state.updateState(createMockTridentTuples(batch1Count), null);
        state.commit(1L);

        // batch 2
        int batch2Count = 20;
        state.beginCommit(2L);
        state.updateState(createMockTridentTuples(batch2Count), null);
        state.commit(2L);

        // batch 3
        int batch3Count = 30;
        state.beginCommit(3L);
        state.updateState(createMockTridentTuples(batch3Count), null);
        state.commit(3L);

        // batch 3 replayed with 40 tuples
        int batch3ReplayCount = 40;
        state.beginCommit(3L);
        state.updateState(createMockTridentTuples(batch3ReplayCount), null);
        state.commit(3L);
        state.close();
        /*
         * total tuples should be
         * recovered (batch-1 + batch-2) + replayed (batch-3)
         */
        List<String> lines = getLinesFromCurrentDataFile();
        int preReplayCount = batch1Count + batch2Count + batch3Count;
        int expectedTupleCount = batch1Count + batch2Count + batch3ReplayCount;

        assertNotEquals(preReplayCount, lines.size());
        assertEquals(expectedTupleCount, lines.size());
    }

    private static class TestFileNameFormat implements FileNameFormat {
        private String currentFileName = "";

        @Override
        public void prepare(Map<String, Object> conf, int partitionIndex, int numPartitions) {

        }

        @Override
        public String getName(long rotation, long timeStamp) {
            currentFileName = FILE_NAME_PREFIX + rotation;
            return currentFileName;
        }

        @Override
        public String getPath() {
            return TEST_OUT_DIR;
        }

        public String getCurrentFileName() {
            return currentFileName;
        }
    }
}
