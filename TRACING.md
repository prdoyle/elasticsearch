# Tracing in Elasticsearch

Elasticsearch is instrumented using the [OpenTelemetry][otel] API, which allows
ES developers to gather traces and analyze what Elasticsearch is doing.

## How is tracing implemented?

The Elasticsearch server code contains a [tracing][tracing] package, which is
an abstraction over the OpenTelemetry API. All locations in the code that
perform instrumentation and tracing must use these abstractions.

Separately, there is the [apm](./modules/apm) module, which works with the
OpenTelemetry API directly to record trace data. Export is pluggable:

* **Elastic [APM Java agent][agent] (default):** The agent attaches to the Elasticsearch
  JVM (via `-javaagent`, see server startup / `APMJvmOptions`) and supplies a real
  OpenTelemetry implementation so the API is not a no-op. Spans are sent to the
  URL configured with `telemetry.agent.server_url` (APM intake / Elastic APM).
* **Elasticsearch-owned OpenTelemetry SDK:** When the JVM system property
  `telemetry.otel.traces.enabled` is set to `true` at startup, Elasticsearch
  uses an embedded OpenTelemetry SDK with **OTLP HTTP** export to the endpoint
  configured under `telemetry.otel.traces.*` (same overall pattern as OTLP
  metrics under `telemetry.otel.metrics.*`). Implementation details live in the
  `apm` module (for example [`OtelSdkSettings`](./modules/apm/src/main/java/org/elasticsearch/telemetry/apm/internal/export/otelsdk/OtelSdkSettings.java)).

The OpenTelemetry API does not bundle an implementation. Without the agent and
without enabling OTLP trace export, the default no-op implementation applies.

## How is tracing configured?

You must supply configuration and credentials for where traces should be sent.

### APM Java agent (Elastic APM / intake)

In your `elasticsearch.yml` add the following configuration:

```
telemetry.tracing.enabled: true
telemetry.agent.server_url: https://<your-apm-server>:443
```

When using a secret token to authenticate with the APM server, you must add it to the Elasticsearch keystore under `telemetry.secret_token`. For example, execute:

    bin/elasticsearch-keystore add telemetry.secret_token

then enter the token when prompted. If you are using API keys, change the keystore key name to `telemetry.api_key`.

All APM-related settings live under `telemetry`. Tracing toggles use
`telemetry.tracing.*`. Settings that are forwarded to the Java agent use
`telemetry.agent.*`; those values are propagated to the agent.

For agent settings that can be changed dynamically, you can use the cluster
settings REST API. For example, to change the sampling rate:

    curl -XPUT \
      -H "Content-type: application/json" \
      -u "$USERNAME:$PASSWORD" \
      -d '{ "persistent": { "telemetry.agent.transaction_sample_rate": "0.75" } }' \
      https://localhost:9200/_cluster/settings

### OpenTelemetry SDK (OTLP HTTP) for traces

To export traces via **OTLP HTTP** (for example to an OpenTelemetry Collector)
instead of the APM agent path, you must enable export at **JVM startup** and
configure the OTLP endpoint in `elasticsearch.yml`:

* Set the JVM system property **`telemetry.otel.traces.enabled=true`** (see
  [`TelemetryProvider`](./server/src/main/java/org/elasticsearch/telemetry/TelemetryProvider.java)).
* Set **`telemetry.otel.traces.endpoint`** to your collector URL, including the
  traces path (for example `https://otel-collector:4318/v1/traces`).

Optional and operational settings in the same namespace include
`telemetry.otel.traces.interval` (batch delay),
`telemetry.otel.traces.max_spans`, and
`telemetry.otel.traces.stack_trace_limit` (defaults align with common Elastic
APM agent defaults such as root-only spans and no stack traces on span errors).
Those policy settings are **dynamic** where marked in
[`OtelSdkSettings`](./modules/apm/src/main/java/org/elasticsearch/telemetry/apm/internal/export/otelsdk/OtelSdkSettings.java).

Authentication for OTLP export uses the same keystore entries as other
telemetry export: **`telemetry.api_key`** or **`telemetry.secret_token`**
(see [`OtelSdkExportMeterSupplier`](./modules/apm/src/main/java/org/elasticsearch/telemetry/apm/internal/export/otelsdk/OtelSdkExportMeterSupplier.java)
for the shared authorization header builder).

When Elasticsearch owns OTLP trace export, the APM agent is not used for
shipping those spans; the node adjusts agent **recording** so the agent is not
left enabled only for trace export when it is no longer needed for that role.

### More details about APM Java agent bootstrap

This section applies when you use the **Elastic APM Java agent** to ship traces
(for example to Elastic APM Server). OTLP HTTP export reads credentials from the
same keystore keys but does **not** use the temporary agent config file described
below.

For context, the APM agent pulls configuration from [multiple
sources][agent-config], with a hierarchy that means, for example, that options
set in the config file cannot be overridden via system properties.

In order to send tracing data via the agent, ES needs to be configured with
either a **secret token** or an **API key** in the keystore. We could configure these in the agent via
system properties, but then their values would be available to any Java code in
Elasticsearch that can read system properties.

Instead, when Elasticsearch bootstraps itself, it compiles all APM settings
together, including any `telemetry.secret_token` or `telemetry.api_key` values from the ES keystore,
and writes out a temporary APM config file containing all static configuration
(i.e. values that cannot change after the agent starts).  This file is deleted
as soon as possible after ES starts up. Settings that are not sensitive and can
be changed dynamically are configured via system properties. Calls to the ES
settings REST API are translated into system property writes, which the agent
later picks up and applies.

## Where is tracing data sent?

For the **agent** path, you need an APM server or Elastic Stack deployment that
accepts Elastic APM intake. For example, you can create a deployment in
[Elastic Cloud](https://www.elastic.co/cloud/) with Elastic's APM integration.

For the **OTLP** path, you need an endpoint that accepts OTLP over HTTP (for
example an OpenTelemetry Collector or compatible backend).

## What do we trace?

We primarily trace "tasks". The tasks framework in Elasticsearch allows work to
be scheduled for execution, cancelled, executed in a different thread pool, and
so on. Tracing a task results in a "span", which represents the execution of the
task in the tracing system. We also instrument REST requests, which are not (at
present) modelled by tasks.

A span can be associated with a parent span, which allows all spans in, for
example, a REST request to be grouped together. Spans can track work across
different Elasticsearch nodes.

Elasticsearch supports distributed tracing via [W3C Trace Context][w3c]
headers. If clients of Elasticsearch send these headers with their requests,
then that context is honored for Elasticsearch-emitted spans whether export
uses the APM agent or the embedded OpenTelemetry SDK (W3C propagators are
configured for OTLP export).

In rare circumstances, it is possible to avoid tracing a task using
`TaskManager#register(String,String,TaskAwareRequest,boolean)`. For example,
Machine Learning uses tasks to record which models are loaded on each node. Such
tasks are long-lived and are not suitable candidates for APM tracing.

## Thread contexts and nested spans

When a span is started, Elasticsearch tracks information about that span in the
current [thread context][thread-context].  If a new thread context is created,
then the current span information must not be propagated but instead renamed, so
that (1) it doesn't interfere when new trace information is set in the context,
and (2) the previous trace information is available to establish a parent /
child span relationship.  This is done with `ThreadContext#newTraceContext()`.

Sometimes we need to detach new spans from their parent. For example, creating
an index starts some related background tasks, but these shouldn't be associated
with the REST request, otherwise all the background task spans will be
associated with the REST request for as long as Elasticsearch is running.
`ThreadContext` provides the `clearTraceContext`() method for this purpose.

## How do I trace something that isn't a task?

First work out if you can turn it into a task. No, really.

If you can't do that, you'll need to ensure that your class can get access to a
`Tracer` instance (this is available to inject, or you'll need to pass it when
your class is created). Then you need to call the appropriate methods on the
tracer when a span should start and end. You'll also need to manage the creation
of new trace contexts when child spans need to be created.

## What additional attributes should I set?

That's up to you. Be careful not to capture anything that could leak sensitive
or personal information.

## What is "scope" and when should I use it?

Usually you won't need to.

That said, sometimes you may want more details to be captured about a particular
section of code. You can think of "scope" as representing the currently active
tracing context. Using scope allows the APM agent to do the following:

* Enables automatic correlation between the "active span" and logging, where
  logs have also been captured.
* Enables capturing any exceptions thrown when the span is active, and linking
  those exceptions to the span
* Allows the sampling profiler to be used as it allows samples to be linked to
  the active span (if any), so the agent can automatically get extra spans
  without manual instrumentation.

However, a scope must be closed in the same thread in which it was opened, which
cannot be guaranteed when using tasks, making scope largely useless to
Elasticsearch.

In the OpenTelemetry documentation, spans, scope and context are fairly
straightforward to use, since `Scope` is an `AutoCloseable` and so can be
easily created and cleaned up using try-with-resources blocks. Unfortunately,
Elasticsearch is a complex piece of software, and also extremely asynchronous,
so the typical OpenTelemetry examples do not work.

Nonetheless, it is possible to manually use scope where we need more detail by
explicitly opening a scope via the `Tracer` API.


[otel]: https://opentelemetry.io/
[thread-context]: ./server/src/main/java/org/elasticsearch/common/util/concurrent/ThreadContext.java
[w3c]: https://www.w3.org/TR/trace-context/
[tracing]: ./server/src/main/java/org/elasticsearch/telemetry
[agent-config]: https://www.elastic.co/guide/en/apm/agent/java/master/configuration.html
[agent]: https://www.elastic.co/guide/en/apm/agent/java/current/index.html
