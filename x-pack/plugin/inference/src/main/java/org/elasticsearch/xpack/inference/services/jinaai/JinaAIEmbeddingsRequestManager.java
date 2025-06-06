/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.jinaai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.inference.external.http.retry.RequestSender;
import org.elasticsearch.xpack.inference.external.http.retry.ResponseHandler;
import org.elasticsearch.xpack.inference.external.http.sender.EmbeddingsInput;
import org.elasticsearch.xpack.inference.external.http.sender.ExecutableInferenceRequest;
import org.elasticsearch.xpack.inference.external.http.sender.InferenceInputs;
import org.elasticsearch.xpack.inference.services.jinaai.embeddings.JinaAIEmbeddingsModel;
import org.elasticsearch.xpack.inference.services.jinaai.request.JinaAIEmbeddingsRequest;
import org.elasticsearch.xpack.inference.services.jinaai.response.JinaAIEmbeddingsResponseEntity;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class JinaAIEmbeddingsRequestManager extends JinaAIRequestManager {
    private static final Logger logger = LogManager.getLogger(JinaAIEmbeddingsRequestManager.class);
    private static final ResponseHandler HANDLER = createEmbeddingsHandler();

    private static ResponseHandler createEmbeddingsHandler() {
        return new JinaAIResponseHandler("jinaai text embedding", JinaAIEmbeddingsResponseEntity::fromResponse);
    }

    public static JinaAIEmbeddingsRequestManager of(JinaAIEmbeddingsModel model, ThreadPool threadPool) {
        return new JinaAIEmbeddingsRequestManager(Objects.requireNonNull(model), Objects.requireNonNull(threadPool));
    }

    private final JinaAIEmbeddingsModel model;

    private JinaAIEmbeddingsRequestManager(JinaAIEmbeddingsModel model, ThreadPool threadPool) {
        super(threadPool, model);
        this.model = Objects.requireNonNull(model);
    }

    @Override
    public void execute(
        InferenceInputs inferenceInputs,
        RequestSender requestSender,
        Supplier<Boolean> hasRequestCompletedFunction,
        ActionListener<InferenceServiceResults> listener
    ) {
        EmbeddingsInput input = EmbeddingsInput.of(inferenceInputs);
        List<String> docsInput = input.getStringInputs();
        InputType inputType = input.getInputType();

        JinaAIEmbeddingsRequest request = new JinaAIEmbeddingsRequest(docsInput, inputType, model);

        execute(new ExecutableInferenceRequest(requestSender, logger, request, HANDLER, hasRequestCompletedFunction, listener));
    }
}
