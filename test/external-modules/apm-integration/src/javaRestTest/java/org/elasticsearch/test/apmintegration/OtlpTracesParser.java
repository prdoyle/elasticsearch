/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.apmintegration;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses OTLP protobuf trace export requests into protocol-neutral {@link ReceivedTelemetry} spans.
 */
public final class OtlpTracesParser {

    private OtlpTracesParser() {}

    public static List<ReceivedTelemetry> parse(InputStream input) throws IOException {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(input);
        List<ReceivedTelemetry> result = new ArrayList<>();
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                for (io.opentelemetry.proto.trace.v1.Span span : scopeSpans.getSpansList()) {
                    String traceId = TraceId.fromBytes(span.getTraceId().toByteArray());
                    String spanId = SpanId.fromBytes(span.getSpanId().toByteArray());
                    Optional<String> parentSpanId = span.getParentSpanId().isEmpty()
                        ? Optional.empty()
                        : Optional.of(SpanId.fromBytes(span.getParentSpanId().toByteArray()));
                    result.add(new ReceivedTelemetry.ReceivedSpan(span.getName(), traceId, spanId, parentSpanId));
                }
            }
        }
        return result;
    }
}
