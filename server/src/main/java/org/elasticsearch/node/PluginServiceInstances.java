
package org.elasticsearch.node;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.DataStreamGlobalRetentionProvider;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.internal.DocumentParsingProvider;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

public record PluginServiceInstances(
    Client client,
    ClusterService clusterService,
    RerouteService rerouteService,
    ThreadPool threadPool,
    ResourceWatcherService resourceWatcherService,
    ScriptService scriptService,
    NamedXContentRegistry xContentRegistry,
    Environment environment,
    NodeEnvironment nodeEnvironment,
    NamedWriteableRegistry namedWriteableRegistry,
    IndexNameExpressionResolver indexNameExpressionResolver,
    RepositoriesService repositoriesService,
    TelemetryProvider telemetryProvider,
    AllocationService allocationService,
    IndicesService indicesService,
    FeatureService featureService,
    SystemIndices systemIndices,
    DataStreamGlobalRetentionProvider dataStreamGlobalRetentionProvider,
    DocumentParsingProvider documentParsingProvider
) implements Plugin.PluginServices {
}
