package org.sagebionetworks.bridge.exporter.metrics;

import java.util.Collection;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.google.common.base.Joiner;
import com.google.common.collect.Multiset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Helper class which handles basic metrics operations. */
@Component
public class MetricsHelper {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsHelper.class);
    private static final Joiner VALUES_TO_LOG_JOINER = Joiner.on(", ").useForNull("null");

    /**
     * Record common per-record metrics.
     *
     * @param metrics
     *         metrics object to record metrics into
     * @param record
     *         DDB health data record object to record metrics for
     */
    public void captureMetricsForRecord(Metrics metrics, Item record) {
        metrics.incrementSetCounter("uniqueHealthCodes[" + record.getString("studyId") + "]",
                record.getString("healthCode"));
    }

    /**
     * Publishes the metrics to the log.
     *
     * @param metrics
     *         metrics object containing metrics to publish
     */
    public void publishMetrics(Metrics metrics) {
        // currently, we publish them to the logs
        for (Multiset.Entry<String> oneCounterEntry : metrics.getCounterMap().entrySet()) {
            LOG.info(oneCounterEntry.getElement() + ": " + oneCounterEntry.getCount());
        }

        for (Map.Entry<String, Collection<String>> oneSetCounterEntry
                : metrics.getSetCounterMap().asMap().entrySet()) {
            LOG.info(oneSetCounterEntry.getKey() + ": " + oneSetCounterEntry.getValue().size());
        }

        for (Map.Entry<String, Collection<String>> oneKeyValueEntry : metrics.getKeyValuesMap().asMap().entrySet()) {
            LOG.info(oneKeyValueEntry.getKey() + ": " + VALUES_TO_LOG_JOINER.join(oneKeyValueEntry.getValue()));
        }
    }
}
