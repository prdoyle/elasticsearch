/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.remoteproject.state;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.test.AbstractXContentTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;

public class RemoteProjectsMetadataTests extends AbstractXContentTestCase<ToXContentWrapper<RemoteProjectsMetadata>> {
    final RemoteProjectsMetadata metadata;
    public RemoteProjectsMetadataTests(@Name("metadata") RemoteProjectsMetadata metadata) {
        this.metadata = metadata;
    }

    @ParametersFactory
    public static List<Object[]> parameters() {
        return List.of(
            args(RemoteProjectsMetadata.EMPTY),
            args(new RemoteProjectsMetadata(List.of(new RemoteLink(
                ProjectId.fromId("projectId"),
                "alias",
                "endpoint",
                List.of("tag1", "tag2")
            ))))
        );
    }

    static Object[] args(RemoteProjectsMetadata metadata) {
        return new Object[] { metadata };
    }

    @Override
    protected ToXContentWrapper<RemoteProjectsMetadata> createTestInstance() {
        return new ToXContentWrapper<>(metadata);
    }

    @Override
    protected ToXContentWrapper<RemoteProjectsMetadata> doParseInstance(XContentParser parser) throws IOException {
        return new  ToXContentWrapper<>(RemoteProjectsMetadata.fromXContent(parser));
    }

    @Override
    protected boolean supportsUnknownFields() {
        return false;
    }
}
