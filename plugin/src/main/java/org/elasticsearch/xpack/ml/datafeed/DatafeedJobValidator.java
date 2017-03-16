/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.messages.Messages;

public final class DatafeedJobValidator {

    private DatafeedJobValidator() {}

    /**
     * Validates a datafeedConfig in relation to the job it refers to
     * @param datafeedConfig the datafeed config
     * @param job the job
     */
    public static void validate(DatafeedConfig datafeedConfig, Job job) {
        AnalysisConfig analysisConfig = job.getAnalysisConfig();
        if (analysisConfig.getLatency() != null && analysisConfig.getLatency().seconds() > 0) {
            throw new IllegalArgumentException(Messages.getMessage(Messages.DATAFEED_DOES_NOT_SUPPORT_JOB_WITH_LATENCY));
        }
        if (datafeedConfig.hasAggregations() && Strings.isNullOrEmpty(analysisConfig.getSummaryCountFieldName())) {
            throw new IllegalArgumentException(
                    Messages.getMessage(Messages.DATAFEED_AGGREGATIONS_REQUIRES_JOB_WITH_SUMMARY_COUNT_FIELD, DatafeedConfig.DOC_COUNT));
        }
    }
}
