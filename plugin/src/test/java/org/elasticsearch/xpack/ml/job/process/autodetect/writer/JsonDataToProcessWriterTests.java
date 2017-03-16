/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect.writer;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.DataDescription.DataFormat;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.ml.job.process.DataCountsReporter;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class JsonDataToProcessWriterTests extends ESTestCase {

    private AutodetectProcess autodetectProcess;
    private DataCountsReporter dataCountsReporter;

    private DataDescription.Builder dataDescription;
    private AnalysisConfig analysisConfig;

    private List<String[]> writtenRecords;

    @Before
    public void setUpMocks() throws IOException {
        autodetectProcess = Mockito.mock(AutodetectProcess.class);
        dataCountsReporter = Mockito.mock(DataCountsReporter.class);

        writtenRecords = new ArrayList<>();
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                String[] record = (String[]) invocation.getArguments()[0];
                String[] copy = Arrays.copyOf(record, record.length);
                writtenRecords.add(copy);
                return null;
            }
        }).when(autodetectProcess).writeRecord(any(String[].class));


        dataDescription = new DataDescription.Builder();
        dataDescription.setFormat(DataFormat.JSON);
        dataDescription.setTimeFormat(DataDescription.EPOCH);

        Detector detector = new Detector.Builder("metric", "value").build();
        analysisConfig = new AnalysisConfig.Builder(Arrays.asList(detector)).build();
    }

    public void testWrite_GivenTimeFormatIsEpochAndDataIsValid() throws Exception {
        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"metric\":\"foo\", \"value\":\"1.0\"}");
        input.append("{\"time\":\"2\", \"metric\":\"bar\", \"value\":\"2.0\"}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"1", "1.0", ""});
        expectedRecords.add(new String[]{"2", "2.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenTimeFormatIsEpochAndTimestampsAreOutOfOrder()
            throws Exception {
        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"3\", \"metric\":\"foo\", \"value\":\"3.0\"}");
        input.append("{\"time\":\"1\", \"metric\":\"bar\", \"value\":\"1.0\"}");
        input.append("{\"time\":\"2\", \"metric\":\"bar\", \"value\":\"2.0\"}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"3", "3.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter, times(2)).reportOutOfOrderRecord(2);
        verify(dataCountsReporter, never()).reportLatestTimeIncrementalStats(anyLong());
        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenTimeFormatIsEpochAndSomeTimestampsWithinLatencySomeOutOfOrder()
            throws Exception {
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "value").build()));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"4\", \"metric\":\"foo\", \"value\":\"4.0\"}");
        input.append("{\"time\":\"5\", \"metric\":\"foo\", \"value\":\"5.0\"}");
        input.append("{\"time\":\"3\", \"metric\":\"bar\", \"value\":\"3.0\"}");
        input.append("{\"time\":\"4\", \"metric\":\"bar\", \"value\":\"4.0\"}");
        input.append("{\"time\":\"2\", \"metric\":\"bar\", \"value\":\"2.0\"}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"4", "4.0", ""});
        expectedRecords.add(new String[]{"5", "5.0", ""});
        expectedRecords.add(new String[]{"3", "3.0", ""});
        expectedRecords.add(new String[]{"4", "4.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter, times(1)).reportOutOfOrderRecord(2);
        verify(dataCountsReporter, never()).reportLatestTimeIncrementalStats(anyLong());
        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenMalformedJsonWithoutNestedLevels()
            throws Exception {
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "value").build()));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"value\":\"1.0\"}");
        input.append("{\"time\":\"2\" \"value\":\"2.0\"}");
        input.append("{\"time\":\"3\", \"value\":\"3.0\"}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"1", "1.0", ""});
        expectedRecords.add(new String[]{"2", "", ""});
        expectedRecords.add(new String[]{"3", "3.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter).reportMissingFields(1);
        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenMalformedJsonWithNestedLevels()
            throws Exception {
        Detector detector = new Detector.Builder("metric", "nested.value").build();
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(detector));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"nested\":{\"value\":\"1.0\"}}");
        input.append("{\"time\":\"2\", \"nested\":{\"value\":\"2.0\"} \"foo\":\"bar\"}");
        input.append("{\"time\":\"3\", \"nested\":{\"value\":\"3.0\"}}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "nested.value", "."});
        expectedRecords.add(new String[]{"1", "1.0", ""});
        expectedRecords.add(new String[]{"2", "2.0", ""});
        expectedRecords.add(new String[]{"3", "3.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenMalformedJsonThatNeverRecovers()
            throws Exception {
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("count", null).build()));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"value\":\"2.0\"}");
        input.append("{\"time");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();

        ESTestCase.expectThrows(ElasticsearchParseException.class, () -> writer.write(inputStream));
    }

    public void testWrite_GivenJsonWithArrayField()
            throws Exception {
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "value").build()));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"array\":[\"foo\", \"bar\"], \"value\":\"1.0\"}");
        input.append("{\"time\":\"2\", \"array\":[], \"value\":\"2.0\"}");
        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"1", "1.0", ""});
        expectedRecords.add(new String[]{"2", "2.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter).finishReporting();
    }

    public void testWrite_GivenJsonWithMissingFields()
            throws Exception {
        AnalysisConfig.Builder builder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "value").build()));
        builder.setLatency(TimeValue.timeValueSeconds(2));
        analysisConfig = builder.build();

        StringBuilder input = new StringBuilder();
        input.append("{\"time\":\"1\", \"f1\":\"foo\", \"value\":\"1.0\"}");
        input.append("{\"time\":\"2\", \"value\":\"2.0\"}");
        input.append("{\"time\":\"3\", \"f1\":\"bar\"}");
        input.append("{}");
        input.append("{\"time\":\"4\", \"value\":\"3.0\"}");

        InputStream inputStream = createInputStream(input.toString());
        JsonDataToProcessWriter writer = createWriter();
        writer.writeHeader();
        writer.write(inputStream);
        verify(dataCountsReporter, times(1)).startNewIncrementalCount();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "value", "."});
        expectedRecords.add(new String[]{"1", "1.0", ""});
        expectedRecords.add(new String[]{"2", "2.0", ""});
        expectedRecords.add(new String[]{"3", "", ""});
        expectedRecords.add(new String[]{"4", "3.0", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(dataCountsReporter, times(1)).reportMissingFields(1L);
        verify(dataCountsReporter, times(1)).reportRecordWritten(2, 1000);
        verify(dataCountsReporter, times(1)).reportRecordWritten(1, 2000);
        verify(dataCountsReporter, times(1)).reportRecordWritten(1, 3000);
        verify(dataCountsReporter, times(1)).reportRecordWritten(1, 4000);
        verify(dataCountsReporter, times(1)).reportDateParseError(0);
        verify(dataCountsReporter).finishReporting();
    }

    private static InputStream createInputStream(String input) {
        return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    }

    private JsonDataToProcessWriter createWriter() {
        return new JsonDataToProcessWriter(true, autodetectProcess, dataDescription.build(), analysisConfig,
                dataCountsReporter);
    }

    private void assertWrittenRecordsEqualTo(List<String[]> expectedRecords) {
        for (int i = 0; i < expectedRecords.size(); i++) {
            assertArrayEquals(expectedRecords.get(i), writtenRecords.get(i));
        }
    }

}
