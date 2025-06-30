/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.remoteproject.state;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public record RemoteLink(
    ProjectId projectId,
    String alias,
    String endpoint,
    List<String> tags
) implements ToXContent {
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("project_id");
        projectId.toXContent(builder, params);
        builder.field("alias", alias);
        builder.field("endpoint", endpoint);
        builder.field("tags", tags);
        builder.endObject();
        return builder;
    }

    public static final String TYPE = "remote_link";

    @SuppressWarnings("unchecked")
    static final ConstructingObjectParser<RemoteLink, Void> PARSER = new ConstructingObjectParser<>(
        TYPE,
        args -> new RemoteLink((ProjectId) args[0], (String) args[1], (String) args[2], (List<String>) args[3])
    );

    static final ParseField PROJECT_ID_FIELD =  new ParseField("project_id");
    static final ParseField ALIAS_FIELD =  new ParseField("alias");
    static final ParseField ENDPOINT_FIELD =  new ParseField("endpoint");
    static final ParseField TAGS_FIELD =  new ParseField("tags");

    static {
        PARSER.declareString(constructorArg(), ProjectId::fromId, PROJECT_ID_FIELD);
        PARSER.declareString(constructorArg(), ALIAS_FIELD);
        PARSER.declareString(constructorArg(), ENDPOINT_FIELD);
        PARSER.declareStringArray(constructorArg(), TAGS_FIELD);
    }

    public static RemoteLink fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }
}
