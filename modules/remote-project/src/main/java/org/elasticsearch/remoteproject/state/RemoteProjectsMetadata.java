/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.remoteproject.state;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.Metadata.ProjectCustom;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

import static org.elasticsearch.cluster.metadata.Metadata.ALL_CONTEXTS;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

/**
 * The root type describing the remote project links for this cluster.
 */
public record RemoteProjectsMetadata(
    List<RemoteLink> remoteLinks
) implements ProjectCustom {
    public static final String TYPE = "remote_projects";

    public static final RemoteProjectsMetadata EMPTY = new RemoteProjectsMetadata(List.of());

    @Override
    public EnumSet<Metadata.XContentContext> context() {
        return ALL_CONTEXTS;
    }

    @Override
    public Diff<ProjectCustom> diff(ProjectCustom previousState) {
        return diff((RemoteProjectsMetadata) previousState);
    }

    private Diff<ProjectCustom> diff(RemoteProjectsMetadata previousState) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getWriteableName() {
        return TYPE;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        // TODO: What is this for?
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // TODO: What is this for?
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<? extends ToXContent> toXContentChunked(ToXContent.Params params) {
        return ChunkedToXContentHelper.array("remote_links", remoteLinks.iterator());
    }

    @SuppressWarnings("unchecked")
    static final ConstructingObjectParser<RemoteProjectsMetadata, Void> PARSER = new ConstructingObjectParser<>(
        TYPE,
        args -> new RemoteProjectsMetadata((List<RemoteLink>) args[0])
    );

    static final ParseField REMOTE_LINKS_FIELD =  new ParseField("remote_links");

    static {
        PARSER.declareObjectArray(constructorArg(), (p, c) -> RemoteLink.fromXContent(p), REMOTE_LINKS_FIELD);
    }

    public static RemoteProjectsMetadata fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }
}
