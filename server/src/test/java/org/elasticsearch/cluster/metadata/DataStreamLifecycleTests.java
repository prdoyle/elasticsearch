/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.action.admin.indices.rollover.RolloverConditions;
import org.elasticsearch.action.admin.indices.rollover.RolloverConfiguration;
import org.elasticsearch.action.admin.indices.rollover.RolloverConfigurationTests;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.AbstractXContentSerializingTestCase;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.elasticsearch.cluster.metadata.DataStreamLifecycle.RetentionSource.DATA_STREAM_CONFIGURATION;
import static org.elasticsearch.cluster.metadata.DataStreamLifecycle.RetentionSource.DEFAULT_GLOBAL_RETENTION;
import static org.elasticsearch.cluster.metadata.DataStreamLifecycle.RetentionSource.MAX_GLOBAL_RETENTION;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class DataStreamLifecycleTests extends AbstractXContentSerializingTestCase<DataStreamLifecycle> {

    @Override
    protected Writeable.Reader<DataStreamLifecycle> instanceReader() {
        return DataStreamLifecycle::new;
    }

    @Override
    protected DataStreamLifecycle createTestInstance() {
        return randomDataLifecycle();
    }

    @Override
    protected DataStreamLifecycle mutateInstance(DataStreamLifecycle instance) throws IOException {
        var enabled = instance.enabled();
        var retention = instance.dataRetention();
        var downsampling = instance.downsampling();
        switch (randomInt(2)) {
            case 0 -> {
                if (retention == null) {
                    retention = randomPositiveTimeValue();
                } else {
                    retention = randomBoolean() ? null : randomValueOtherThan(retention, ESTestCase::randomPositiveTimeValue);
                }
            }
            case 1 -> {
                if (downsampling == null) {
                    downsampling = randomDownsampling();
                } else {
                    downsampling = randomBoolean()
                        ? null
                        : randomValueOtherThan(downsampling, DataStreamLifecycleTests::randomDownsampling);
                }
            }
            default -> enabled = enabled == false;
        }
        return new DataStreamLifecycle(enabled, retention, downsampling);
    }

    @Override
    protected DataStreamLifecycle doParseInstance(XContentParser parser) throws IOException {
        return DataStreamLifecycle.fromXContent(parser);
    }

    public void testXContentSerializationWithRolloverAndEffectiveRetention() throws IOException {
        DataStreamLifecycle lifecycle = createTestInstance();
        try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
            builder.humanReadable(true);
            RolloverConfiguration rolloverConfiguration = RolloverConfigurationTests.randomRolloverConditions();
            DataStreamGlobalRetention globalRetention = DataStreamGlobalRetentionTests.randomGlobalRetention();
            ToXContent.Params withEffectiveRetention = new ToXContent.MapParams(DataStreamLifecycle.INCLUDE_EFFECTIVE_RETENTION_PARAMS);
            lifecycle.toXContent(builder, withEffectiveRetention, rolloverConfiguration, globalRetention, false);
            String serialized = Strings.toString(builder);
            assertThat(serialized, containsString("rollover"));
            for (String label : rolloverConfiguration.resolveRolloverConditions(lifecycle.getEffectiveDataRetention(globalRetention, false))
                .getConditions()
                .keySet()) {
                assertThat(serialized, containsString(label));
            }
            // Verify that max_age is marked as automatic, if it's set on auto
            if (rolloverConfiguration.getAutomaticConditions().isEmpty() == false) {
                assertThat(serialized, containsString("[automatic]"));
            }
            // We check that even if there was no retention provided by the user, the global retention applies
            if (lifecycle.dataRetention() == null) {
                assertThat(serialized, not(containsString("data_retention")));
            } else {
                assertThat(serialized, containsString("data_retention"));
            }
            boolean globalRetentionIsNotNull = globalRetention.defaultRetention() != null || globalRetention.maxRetention() != null;
            boolean configuredLifeCycleIsNotNull = lifecycle.dataRetention() != null;
            if (lifecycle.enabled() && (globalRetentionIsNotNull || configuredLifeCycleIsNotNull)) {
                assertThat(serialized, containsString("effective_retention"));
            } else {
                assertThat(serialized, not(containsString("effective_retention")));
            }
        }
    }

    public void testXContentDeSerializationWithNullValues() throws IOException {
        String lifecycleJson = """
            {
              "data_retention": null,
              "downsampling": null
            }
            """;
        try (XContentParser parser = createParser(XContentType.JSON.xContent(), lifecycleJson)) {
            assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
            var parsed = DataStreamLifecycle.fromXContent(parser);
            assertEquals(XContentParser.Token.END_OBJECT, parser.currentToken());
            assertNull(parser.nextToken());
            assertThat(parsed, equalTo(DataStreamLifecycle.DEFAULT_DATA_LIFECYCLE));
        }
    }

    public void testDefaultClusterSetting() {
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        RolloverConfiguration rolloverConfiguration = clusterSettings.get(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING);
        assertThat(rolloverConfiguration.getAutomaticConditions(), equalTo(Set.of("max_age")));
        RolloverConditions concreteConditions = rolloverConfiguration.getConcreteConditions();
        assertThat(concreteConditions.getMaxPrimaryShardSize(), equalTo(ByteSizeValue.ofGb(50)));
        assertThat(concreteConditions.getMaxPrimaryShardDocs(), equalTo(200_000_000L));
        assertThat(concreteConditions.getMinDocs(), equalTo(1L));
        assertThat(concreteConditions.getMaxSize(), nullValue());
        assertThat(concreteConditions.getMaxDocs(), nullValue());
        assertThat(concreteConditions.getMinAge(), nullValue());
        assertThat(concreteConditions.getMinSize(), nullValue());
        assertThat(concreteConditions.getMinPrimaryShardSize(), nullValue());
        assertThat(concreteConditions.getMinPrimaryShardDocs(), nullValue());
        assertThat(concreteConditions.getMaxAge(), nullValue());
    }

    public void testInvalidClusterSetting() {
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING.get(
                    Settings.builder().put(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING.getKey(), "").build()
                )
            );
            assertThat(exception.getMessage(), equalTo("The rollover conditions cannot be null or blank"));
        }
    }

    public void testInvalidDownsamplingConfiguration() {
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder()
                    .downsampling(
                        List.of(
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(10),
                                new DownsampleConfig(new DateHistogramInterval("2h"))
                            ),
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(3),
                                new DownsampleConfig(new DateHistogramInterval("2h"))
                            )
                        )
                    )
                    .build()
            );
            assertThat(
                exception.getMessage(),
                equalTo("A downsampling round must have a later 'after' value than the proceeding, 3d is not after 10d.")
            );
        }
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder()
                    .downsampling(
                        List.of(
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(10),
                                new DownsampleConfig(new DateHistogramInterval("2h"))
                            ),
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(30),
                                new DownsampleConfig(new DateHistogramInterval("2h"))
                            )
                        )
                    )
                    .build()
            );
            assertThat(exception.getMessage(), equalTo("Downsampling interval [2h] must be greater than the source index interval [2h]."));
        }
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder()
                    .downsampling(
                        List.of(
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(10),
                                new DownsampleConfig(new DateHistogramInterval("2h"))
                            ),
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(30),
                                new DownsampleConfig(new DateHistogramInterval("3h"))
                            )
                        )
                    )
                    .build()
            );
            assertThat(exception.getMessage(), equalTo("Downsampling interval [3h] must be a multiple of the source index interval [2h]."));
        }
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder().downsampling((List.of())).build()
            );
            assertThat(exception.getMessage(), equalTo("Downsampling configuration should have at least one round configured."));
        }
        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder()
                    .downsampling(
                        Stream.iterate(1, i -> i * 2)
                            .limit(12)
                            .map(
                                i -> new DataStreamLifecycle.DownsamplingRound(
                                    TimeValue.timeValueDays(i),
                                    new DownsampleConfig(new DateHistogramInterval(i + "h"))
                                )
                            )
                            .toList()
                    )
                    .build()
            );
            assertThat(exception.getMessage(), equalTo("Downsampling configuration supports maximum 10 configured rounds. Found: 12"));
        }

        {
            IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> DataStreamLifecycle.dataLifecycleBuilder()
                    .downsampling(
                        List.of(
                            new DataStreamLifecycle.DownsamplingRound(
                                TimeValue.timeValueDays(10),
                                new DownsampleConfig(new DateHistogramInterval("2m"))
                            )
                        )
                    )
                    .build()
            );
            assertThat(
                exception.getMessage(),
                equalTo("A downsampling round must have a fixed interval of at least five minutes but found: 2m")
            );
        }
    }

    public void testEffectiveRetention() {
        // No retention in the data stream lifecycle
        {
            DataStreamLifecycle noRetentionLifecycle = DataStreamLifecycle.dataLifecycleBuilder()
                .downsampling(randomDownsampling())
                .build();
            TimeValue maxRetention = TimeValue.timeValueDays(randomIntBetween(50, 100));
            TimeValue defaultRetention = TimeValue.timeValueDays(randomIntBetween(1, 50));
            Tuple<TimeValue, DataStreamLifecycle.RetentionSource> effectiveDataRetentionWithSource = noRetentionLifecycle
                .getEffectiveDataRetentionWithSource(null, randomBoolean());
            assertThat(effectiveDataRetentionWithSource.v1(), nullValue());
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DATA_STREAM_CONFIGURATION));

            effectiveDataRetentionWithSource = noRetentionLifecycle.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(null, maxRetention),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(maxRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(MAX_GLOBAL_RETENTION));

            effectiveDataRetentionWithSource = noRetentionLifecycle.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(defaultRetention, null),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(defaultRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DEFAULT_GLOBAL_RETENTION));

            effectiveDataRetentionWithSource = noRetentionLifecycle.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(defaultRetention, maxRetention),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(defaultRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DEFAULT_GLOBAL_RETENTION));
        }

        // With retention in the data stream lifecycle
        {
            TimeValue dataStreamRetention = TimeValue.timeValueDays(randomIntBetween(5, 100));
            DataStreamLifecycle lifecycleRetention = DataStreamLifecycle.dataLifecycleBuilder()
                .dataRetention(dataStreamRetention)
                .downsampling(randomDownsampling())
                .build();
            TimeValue defaultRetention = TimeValue.timeValueDays(randomIntBetween(1, (int) dataStreamRetention.getDays() - 1));

            Tuple<TimeValue, DataStreamLifecycle.RetentionSource> effectiveDataRetentionWithSource = lifecycleRetention
                .getEffectiveDataRetentionWithSource(null, randomBoolean());
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(dataStreamRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DATA_STREAM_CONFIGURATION));

            effectiveDataRetentionWithSource = lifecycleRetention.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(defaultRetention, null),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(dataStreamRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DATA_STREAM_CONFIGURATION));

            TimeValue maxGlobalRetention = randomBoolean() ? dataStreamRetention : TimeValue.timeValueDays(dataStreamRetention.days() + 1);
            effectiveDataRetentionWithSource = lifecycleRetention.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(defaultRetention, maxGlobalRetention),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(dataStreamRetention));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DATA_STREAM_CONFIGURATION));

            TimeValue maxRetentionLessThanDataStream = TimeValue.timeValueDays(dataStreamRetention.days() - 1);
            effectiveDataRetentionWithSource = lifecycleRetention.getEffectiveDataRetentionWithSource(
                new DataStreamGlobalRetention(
                    randomBoolean()
                        ? null
                        : TimeValue.timeValueDays(randomIntBetween(1, (int) (maxRetentionLessThanDataStream.days() - 1))),
                    maxRetentionLessThanDataStream
                ),
                false
            );
            assertThat(effectiveDataRetentionWithSource.v1(), equalTo(maxRetentionLessThanDataStream));
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(MAX_GLOBAL_RETENTION));
        }

        // Global retention does not apply to internal data streams
        {
            // Pick the values in such a way that global retention should have kicked in
            boolean dataStreamWithRetention = randomBoolean();
            TimeValue dataStreamRetention = dataStreamWithRetention ? TimeValue.timeValueDays(365) : null;
            DataStreamGlobalRetention globalRetention = new DataStreamGlobalRetention(
                TimeValue.timeValueDays(7),
                TimeValue.timeValueDays(90)
            );
            DataStreamLifecycle lifecycle = DataStreamLifecycle.dataLifecycleBuilder().dataRetention(dataStreamRetention).build();

            // Verify that global retention should have kicked in
            var effectiveDataRetentionWithSource = lifecycle.getEffectiveDataRetentionWithSource(globalRetention, false);
            if (dataStreamWithRetention) {
                assertThat(effectiveDataRetentionWithSource.v1(), equalTo(globalRetention.maxRetention()));
                assertThat(effectiveDataRetentionWithSource.v2(), equalTo(MAX_GLOBAL_RETENTION));
            } else {
                assertThat(effectiveDataRetentionWithSource.v1(), equalTo(globalRetention.defaultRetention()));
                assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DEFAULT_GLOBAL_RETENTION));
            }

            // Now verify that internal data streams do not use global retention
            // Verify that global retention should have kicked in
            effectiveDataRetentionWithSource = lifecycle.getEffectiveDataRetentionWithSource(globalRetention, true);
            if (dataStreamWithRetention) {
                assertThat(effectiveDataRetentionWithSource.v1(), equalTo(dataStreamRetention));
            } else {
                assertThat(effectiveDataRetentionWithSource.v1(), nullValue());
            }
            assertThat(effectiveDataRetentionWithSource.v2(), equalTo(DATA_STREAM_CONFIGURATION));
        }
    }

    public void testEffectiveRetentionParams() {
        Map<String, String> initialParams = randomMap(0, 10, () -> Tuple.tuple(randomAlphaOfLength(10), randomAlphaOfLength(10)));
        ToXContent.Params params = DataStreamLifecycle.addEffectiveRetentionParams(new ToXContent.MapParams(initialParams));
        assertThat(params.paramAsBoolean(DataStreamLifecycle.INCLUDE_EFFECTIVE_RETENTION_PARAM_NAME, false), equalTo(true));
        for (String key : initialParams.keySet()) {
            assertThat(initialParams.get(key), equalTo(params.param(key)));
        }
    }

    public static DataStreamLifecycle randomDataLifecycle() {
        return DataStreamLifecycle.dataLifecycleBuilder()
            .dataRetention(randomBoolean() ? null : randomTimeValue(1, 365, TimeUnit.DAYS))
            .downsampling(randomBoolean() ? null : randomDownsampling())
            .enabled(randomBoolean())
            .build();
    }

    static List<DataStreamLifecycle.DownsamplingRound> randomDownsampling() {
        var count = randomIntBetween(0, 9);
        List<DataStreamLifecycle.DownsamplingRound> rounds = new ArrayList<>();
        var previous = new DataStreamLifecycle.DownsamplingRound(
            randomTimeValue(1, 365, TimeUnit.DAYS),
            new DownsampleConfig(new DateHistogramInterval(randomIntBetween(1, 24) + "h"))
        );
        rounds.add(previous);
        for (int i = 0; i < count; i++) {
            DataStreamLifecycle.DownsamplingRound round = nextRound(previous);
            rounds.add(round);
            previous = round;
        }
        return rounds;
    }

    private static DataStreamLifecycle.DownsamplingRound nextRound(DataStreamLifecycle.DownsamplingRound previous) {
        var after = TimeValue.timeValueDays(previous.after().days() + randomIntBetween(1, 10));
        var fixedInterval = new DownsampleConfig(
            new DateHistogramInterval((previous.config().getFixedInterval().estimateMillis() * randomIntBetween(2, 5)) + "ms")
        );
        return new DataStreamLifecycle.DownsamplingRound(after, fixedInterval);
    }
}
