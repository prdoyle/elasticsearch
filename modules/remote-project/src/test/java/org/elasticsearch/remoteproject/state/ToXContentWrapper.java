package org.elasticsearch.remoteproject.state;

import org.elasticsearch.common.xcontent.ChunkedToXContent;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Iterator;

public record ToXContentWrapper<T extends ChunkedToXContent>(
    T target
) implements ToXContent {
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        for (Iterator<? extends ToXContent> it = target.toXContentChunked(params); it.hasNext(); ) {
            it.next().toXContent(builder, params);
        }
        return builder;
    }
}
