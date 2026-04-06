/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.telemetry.apm.internal.APMAgentSettings;
import org.elasticsearch.telemetry.apm.internal.export.otelsdk.OtelSdkSettings;
import org.elasticsearch.telemetry.apm.internal.export.otelsdk.OtelSdkTelemetryResources;
import org.elasticsearch.telemetry.tracing.TraceContext;
import org.elasticsearch.telemetry.tracing.Traceable;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_METRICS_ENABLED_SYSTEM_PROPERTY;
import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_TRACES_ENABLED_SYSTEM_PROPERTY;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@TestLogging(reason = "improved visibility", value = "org.elasticsearch.telemetry.apm.internal.tracing:TRACE")
public class APMTracerTests extends ESTestCase {

    private static final Traceable TRACEABLE1 = new TestTraceable("id1");
    private static final Traceable TRACEABLE2 = new TestTraceable("id2");
    private static final Traceable TRACEABLE3 = new TestTraceable("id3");

    /**
     * Check that the tracer doesn't create spans when tracing is disabled.
     */
    public void test_onTraceStarted_withTracingDisabled_doesNotStartTrace() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), false).build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name1", null);

        assertThat(apmTracer.getSpans(), anEmptyMap());
    }

    /**
     * Check that the tracer doesn't create spans if a Traceable's span name is filtered out.
     */
    public void test_onTraceStarted_withSpanNameOmitted_doesNotStartTrace() {
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .putList(APMAgentSettings.TELEMETRY_TRACING_NAMES_INCLUDE_SETTING.getKey(), List.of("filtered*"))
            .build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name1", null);

        assertThat(apmTracer.getSpans(), anEmptyMap());
    }

    /**
     * Check that when a trace is started, the tracer starts a span and records it.
     */
    public void test_onTraceStarted_startsTrace() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        ThreadContext traceContext = new ThreadContext(settings);
        apmTracer.startTrace(traceContext, TRACEABLE1, "name1", null);

        assertThat(traceContext.getTransient(Task.APM_TRACE_CONTEXT), notNullValue());
        assertThat(apmTracer.getSpans(), aMapWithSize(1));
        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE1.getSpanId()));
    }

    /**
     * Check that when a root trace is started, but it is not recorded, e.g. due to sampling,
     * the tracer tracks it but doesn't start tracing.
     */
    public void test_onTraceStarted_ifNotRecorded_doesNotStartTracing() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        ThreadContext traceContext = new ThreadContext(settings);
        apmTracer.startTrace(traceContext, TRACEABLE1, "name1_discard", null);

        assertThat(traceContext.getTransient(Task.APM_TRACE_CONTEXT), nullValue());
        assertThat(apmTracer.getSpans(), anEmptyMap());
    }

    /**
     * Check that when a nested trace is discarded e.g.g due to transaction_max_spans exceeded, the tracer does not record it.
     */
    public void test_onNestedTraceStarted_ifNotRecorded_doesNotStartTrace() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        ThreadContext traceContext = new ThreadContext(settings);
        apmTracer.startTrace(traceContext, TRACEABLE1, "name1", null);
        try (var ignore1 = traceContext.newTraceContext()) {
            apmTracer.startTrace(traceContext, TRACEABLE2, "name2_discard", null);
            assertThat(traceContext.getTransient(Task.APM_TRACE_CONTEXT), nullValue());

            try (var ignore2 = traceContext.newTraceContext()) {
                apmTracer.startTrace(traceContext, TRACEABLE3, "name3_discard", null);
                assertThat(traceContext.getTransient(Task.APM_TRACE_CONTEXT), nullValue());
            }
        }
        assertThat(apmTracer.getSpans(), aMapWithSize(1));
        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE1.getSpanId()));
    }

    /**
     * Checks that when a trace is started with a specific start time, the tracer starts a span and records it.
     */
    public void test_onTraceStartedWithStartTime_startsTrace() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        TraceContext traceContext = new ThreadContext(settings);
        // 1_000_000L because of "toNanos" conversions that overflow for large long millis
        Instant spanStartTime = Instant.ofEpochMilli(randomLongBetween(0, Long.MAX_VALUE / 1_000_000L));
        traceContext.putTransient(Task.TRACE_START_TIME, spanStartTime);
        apmTracer.startTrace(traceContext, TRACEABLE1, "name1", null);

        assertThat(traceContext.getTransient(Task.APM_TRACE_CONTEXT), notNullValue());
        assertThat(apmTracer.getSpans(), aMapWithSize(1));
        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE1.getSpanId()));
        assertThat(((SpyAPMTracer) apmTracer).getSpanStartTime("name1"), is(spanStartTime));
    }

    /**
     * Check that when a trace is stopped, the tracer ends the span and removes the record of it.
     */
    public void test_onTraceStopped_stopsTrace() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name1", null);
        apmTracer.stopTrace(TRACEABLE1);
        apmTracer.stopTrace(TRACEABLE2); // stopping a non-existent trace is a noop

        assertThat(apmTracer.getSpans(), anEmptyMap());
    }

    /**
     * Check that when a trace is started, then the thread context is updated with tracing information.
     * <p>
     * We expect the APM agent to inject the {@link Task#TRACE_PARENT_HTTP_HEADER} and {@link Task#TRACE_STATE}
     * headers into the context, and it does, but this doesn't happen in the unit tests. We can
     * check that the local context object is added, however.
     */
    public void test_whenTraceStarted_threadContextIsPopulated() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true).build();
        APMTracer apmTracer = buildTracer(settings);

        ThreadContext threadContext = new ThreadContext(settings);
        apmTracer.startTrace(threadContext, TRACEABLE1, "name1", null);
        assertThat(threadContext.getTransient(Task.APM_TRACE_CONTEXT), notNullValue());
    }

    /**
     * Check that when a tracer has a list of include names configured, then those
     * names are used to filter spans.
     */
    public void test_whenTraceStarted_andSpanNameIncluded_thenSpanIsStarted() {
        final List<String> includePatterns = List.of(
            // exact name
            "name-aaa",
            // regex
            "name-b*"
        );
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .putList(APMAgentSettings.TELEMETRY_TRACING_NAMES_INCLUDE_SETTING.getKey(), includePatterns)
            .build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name-aaa", null);
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE2, "name-bbb", null);
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE3, "name-ccc", null);

        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE1.getSpanId()));
        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE2.getSpanId()));
        assertThat(apmTracer.getSpans(), not(hasKey(TRACEABLE3.getSpanId())));
    }

    /**
     * Check that when a tracer has a list of include and exclude names configured, and
     * a span matches both, then the exclude filters take precedence.
     */
    public void test_whenTraceStarted_andSpanNameIncludedAndExcluded_thenSpanIsNotStarted() {
        final List<String> includePatterns = List.of("name-a*");
        final List<String> excludePatterns = List.of("name-a*");
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .putList(APMAgentSettings.TELEMETRY_TRACING_NAMES_INCLUDE_SETTING.getKey(), includePatterns)
            .putList(APMAgentSettings.TELEMETRY_TRACING_NAMES_EXCLUDE_SETTING.getKey(), excludePatterns)
            .build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name-aaa", null);

        assertThat(apmTracer.getSpans(), not(hasKey("id1")));
    }

    /**
     * Check that when a tracer has a list of exclude names configured, then those
     * names are used to filter spans.
     */
    public void test_whenTraceStarted_andSpanNameExcluded_thenSpanIsNotStarted() {
        final List<String> excludePatterns = List.of(
            // exact name
            "name-aaa",
            // regex
            "name-b*"
        );
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .putList(APMAgentSettings.TELEMETRY_TRACING_NAMES_EXCLUDE_SETTING.getKey(), excludePatterns)
            .build();
        APMTracer apmTracer = buildTracer(settings);

        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name-aaa", null);
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE2, "name-bbb", null);
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE3, "name-ccc", null);

        assertThat(apmTracer.getSpans(), not(hasKey(TRACEABLE1.getSpanId())));
        assertThat(apmTracer.getSpans(), not(hasKey(TRACEABLE2.getSpanId())));
        assertThat(apmTracer.getSpans(), hasKey(TRACEABLE3.getSpanId()));
    }

    /**
     * Check that sensitive attributes are not added verbatim to a span, but instead the value is redacted.
     */
    /**
     * Agent path: {@code stack_trace_limit=0} should record errors as status + exception attributes, not {@link Span#recordException(Throwable)}.
     */
    public void test_addError_stackTraceLimitZero_setsStatusWithoutRecordingException() {
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .put("telemetry.agent.stack_trace_limit", "0")
            .build();
        SpyAPMTracer apmTracer = new SpyAPMTracer(settings);
        apmTracer.doStart();
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name1", null);
        RuntimeException ex = new RuntimeException("boom");
        apmTracer.addError(TRACEABLE1, ex);
        verify(apmTracer.lastStartedSpan, never()).recordException(any());
        verify(apmTracer.lastStartedSpan).setStatus(StatusCode.ERROR, "boom");
    }

    /**
     * Agent path: positive {@code stack_trace_limit} should use {@link Span#recordException(Throwable)}.
     */
    public void test_addError_stackTraceLimitPositive_recordsException() {
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .put("telemetry.agent.stack_trace_limit", "8")
            .build();
        SpyAPMTracer apmTracer = new SpyAPMTracer(settings);
        apmTracer.doStart();
        apmTracer.startTrace(new ThreadContext(settings), TRACEABLE1, "name1", null);
        RuntimeException ex = new RuntimeException("boom");
        apmTracer.addError(TRACEABLE1, ex);
        verify(apmTracer.lastStartedSpan).recordException(ex);
    }

    /**
     * Cluster updates to {@code telemetry.otel.traces.max_spans} must not override agent limits when the APM agent owns export.
     */
    public void test_setMaxChildSpans_ignoredWhenAgentOwnsTraces() {
        Settings settings = Settings.builder()
            .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
            .put("telemetry.agent.transaction_max_spans", "4")
            .build();
        SpyAPMTracer apmTracer = new SpyAPMTracer(settings);
        assertThat(apmTracer.getMaxChildSpansForTesting(), is(4));
        apmTracer.setMaxChildSpans(1);
        assertThat(apmTracer.getMaxChildSpansForTesting(), is(4));
    }

    /**
     * When Elasticsearch owns OTLP trace export, dynamic {@link APMTracer#setMaxChildSpans} updates apply.
     */
    @SuppressForbidden(reason = "tests OTLP trace JVM gate")
    public void test_setMaxChildSpans_appliedWhenOtelSdkOwnsTraces() throws Exception {
        String prevTraces = System.getProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY);
        String prevMetrics = System.getProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY);
        try {
            System.setProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY, "true");
            System.clearProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY);
            Settings settings = Settings.builder()
                .put(OtelSdkSettings.TELEMETRY_OTEL_TRACES_ENDPOINT.getKey(), "http://127.0.0.1:9/v1/traces")
                .put(OtelSdkSettings.TELEMETRY_OTEL_TRACES_MAX_SPANS.getKey(), "5")
                .put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), true)
                .build();
            try (OtelSdkTelemetryResources resources = OtelSdkTelemetryResources.maybeCreate(settings)) {
                APMTracer tracer = new APMTracer(settings, resources);
                assertThat(tracer.getMaxChildSpansForTesting(), is(5));
                tracer.setMaxChildSpans(2);
                assertThat(tracer.getMaxChildSpansForTesting(), is(2));
            }
        } finally {
            if (prevTraces == null) {
                System.clearProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY);
            } else {
                System.setProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY, prevTraces);
            }
            if (prevMetrics == null) {
                System.clearProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY);
            } else {
                System.setProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY, prevMetrics);
            }
        }
    }

    public void test_whenAddingAttributes_thenSensitiveValuesAreRedacted() {
        Settings settings = Settings.builder().put(APMAgentSettings.TELEMETRY_TRACING_ENABLED_SETTING.getKey(), false).build();
        APMTracer apmTracer = buildTracer(settings);
        CharacterRunAutomaton labelFilterAutomaton = apmTracer.getLabelFilterAutomaton();

        Stream.of(
            "auth",
            "auth-header",
            "authValue",
            "card",
            "card-details",
            "card-number",
            "credit",
            "credit-card",
            "key",
            "my-credit-number",
            "my_session_id",
            "passwd",
            "password",
            "principal",
            "principal-value",
            "pwd",
            "secret",
            "secure-key",
            "sensitive-token*",
            "session",
            "session_id",
            "set-cookie",
            "some-auth",
            "some-principal",
            "token-for login"
        ).forEach(key -> assertTrue("Expected label filter automaton to redact [" + key + "]", labelFilterAutomaton.run(key)));
    }

    private APMTracer buildTracer(Settings settings) {
        APMTracer tracer = new SpyAPMTracer(settings);
        tracer.doStart();
        return tracer;
    }

    static class SpyAPMTracer extends APMTracer {

        Map<String, Instant> spanStartTimeMap;
        Span lastStartedSpan;

        SpyAPMTracer(Settings settings) {
            super(settings);
            this.spanStartTimeMap = new HashMap<>();
        }

        @Override
        APMServices createApmServices() {
            APMServices apmServices = super.createApmServices();
            Tracer mockTracer = mock(Tracer.class);
            doAnswer(invocation -> {
                String spanName = (String) invocation.getArguments()[0];
                return new MockSpanBuilder(spanName);
            }).when(mockTracer).spanBuilder(anyString());
            return new APMServices(mockTracer, apmServices.openTelemetry());
        }

        Instant getSpanStartTime(String spanName) {
            return spanStartTimeMap.get(spanName);
        }

        /**
         * There's no APM agent in unit tests. Spans created by the default span builder would be NOOP spans that are not recorded.
         * This builder simulates recorded spans so that we can test the tracer behavior.
         */
        class MockSpanBuilder implements SpanBuilder {

            Span span;
            Instant startTime;
            String spanName;

            MockSpanBuilder(String spanName) {
                this.spanName = spanName;
                this.span = Mockito.mock(Span.class, spanName);
                // simulate discarded span due to transaction_max_spans exceeded
                Mockito.when(span.isRecording()).thenReturn(spanName.endsWith("_discard") == false);
                Mockito.when(span.storeInContext(Mockito.any(Context.class))).thenCallRealMethod();
            }

            @Override
            public SpanBuilder setParent(Context context) {
                SpanContext spanContext = Span.fromContext(context).getSpanContext();
                Mockito.when(span.getSpanContext()).thenReturn(spanContext);
                return this;
            }

            @Override
            public SpanBuilder setNoParent() {
                SpanContext invalid = SpanContext.getInvalid();
                Mockito.when(span.getSpanContext()).thenReturn(invalid);
                return this;
            }

            @Override
            public SpanBuilder addLink(SpanContext spanContext) {
                return this;
            }

            @Override
            public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
                return this;
            }

            @Override
            public SpanBuilder setAttribute(String key, String value) {
                return this;
            }

            @Override
            public SpanBuilder setAttribute(String key, long value) {
                return this;
            }

            @Override
            public SpanBuilder setAttribute(String key, double value) {
                return this;
            }

            @Override
            public SpanBuilder setAttribute(String key, boolean value) {
                return this;
            }

            @Override
            public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
                return this;
            }

            @Override
            public SpanBuilder setSpanKind(SpanKind spanKind) {
                return this;
            }

            @Override
            public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
                startTime = Instant.ofEpochMilli(TimeUnit.MILLISECONDS.convert(startTimestamp, unit));
                return this;
            }

            @Override
            public Span startSpan() {
                // finally record the spanName-startTime association when the span is actually started
                SpyAPMTracer.this.lastStartedSpan = span;
                spanStartTimeMap.put(spanName, startTime);
                return span;
            }
        }
    }

    private static class TestTraceable implements Traceable {
        private final String spanId;

        TestTraceable(String spanId) {
            this.spanId = Objects.requireNonNull(spanId);
        }

        @Override
        public String getSpanId() {
            return spanId;
        }
    }
}
