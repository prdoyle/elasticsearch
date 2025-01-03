/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.qa.common;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyBreakIteratorProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyCalendarDataProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyCalendarNameProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyCollatorProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyCurrencyNameProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyDateFormatProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyDateFormatSymbolsProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyDecimalFormatSymbolsProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyLocaleNameProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyLocaleServiceProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyNumberFormatProvider;
import org.elasticsearch.entitlement.qa.common.DummyLocaleProviders.DummyTimeZoneNameProvider;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import static java.util.Map.entry;
import static org.elasticsearch.entitlement.qa.common.RestEntitlementsCheckAction.CheckAction.alwaysDenied;
import static org.elasticsearch.entitlement.qa.common.RestEntitlementsCheckAction.CheckAction.deniedToPlugins;
import static org.elasticsearch.entitlement.qa.common.RestEntitlementsCheckAction.CheckAction.forPlugins;
import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestEntitlementsCheckAction extends BaseRestHandler {
    private static final Logger logger = LogManager.getLogger(RestEntitlementsCheckAction.class);
    private final String prefix;

    record CheckAction(Runnable action, boolean isAlwaysDeniedToPlugins) {
        /**
         * These cannot be granted to plugins, so our test plugins cannot test the "allowed" case.
         * Used both for always-denied entitlements as well as those granted only to the server itself.
         */
        static CheckAction deniedToPlugins(Runnable action) {
            return new CheckAction(action, true);
        }

        static CheckAction forPlugins(Runnable action) {
            return new CheckAction(action, false);
        }

        static CheckAction alwaysDenied(Runnable action) {
            return new CheckAction(action, true);
        }
    }

    private static final Map<String, CheckAction> checkActions;

    static {
        checkActions = Map.ofEntries(
            entry("runtime_exit", deniedToPlugins(RestEntitlementsCheckAction::runtimeExit)),
            entry("runtime_halt", deniedToPlugins(RestEntitlementsCheckAction::runtimeHalt)),
            entry("create_classloader", forPlugins(RestEntitlementsCheckAction::createClassLoader)),
            entry("processBuilder_start", deniedToPlugins(RestEntitlementsCheckAction::processBuilder_start)),
            entry("processBuilder_startPipeline", deniedToPlugins(RestEntitlementsCheckAction::processBuilder_startPipeline)),
            entry("set_https_connection_properties", forPlugins(RestEntitlementsCheckAction::setHttpsConnectionProperties)),
            entry("set_default_ssl_socket_factory", alwaysDenied(RestEntitlementsCheckAction::setDefaultSSLSocketFactory)),
            entry("set_default_hostname_verifier", alwaysDenied(RestEntitlementsCheckAction::setDefaultHostnameVerifier)),
            entry("set_default_ssl_context", alwaysDenied(RestEntitlementsCheckAction::setDefaultSSLContext))
        );
    }

    private static void setDefaultSSLContext() {
        try {
            SSLContext.setDefault(SSLContext.getDefault());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setDefaultHostnameVerifier() {
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> false);
    }

    private static void setDefaultSSLSocketFactory() {
        HttpsURLConnection.setDefaultSSLSocketFactory(new TestSSLSocketFactory());
    }

    @SuppressForbidden(reason = "Specifically testing Runtime.exit")
    private static void runtimeExit() {
        Runtime.getRuntime().exit(123);
    }

    @SuppressForbidden(reason = "Specifically testing Runtime.halt")
    private static void runtimeHalt() {
        Runtime.getRuntime().halt(123);
    }

    private static void createClassLoader() {
        try (var classLoader = new URLClassLoader("test", new URL[0], RestEntitlementsCheckAction.class.getClassLoader())) {
            logger.info("Created URLClassLoader [{}]", classLoader.getName());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void processBuilder_start() {
        try {
            new ProcessBuilder("").start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void processBuilder_startPipeline() {
        try {
            ProcessBuilder.startPipeline(List.of());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setHttpsConnectionProperties() {
        new TestHttpsURLConnection().setSSLSocketFactory(new TestSSLSocketFactory());
    }

    private static void system$$setIn() {
        System.setIn(System.in);
    }

    private static void system$$setOut() {
        System.setOut(System.out);
    }

    private static void system$$setErr() {
        System.setErr(System.err);
    }

    private static void runtime$addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
    }

    private static void runtime$$removeShutdownHook() {
        Runtime.getRuntime().removeShutdownHook(new Thread(() -> {}));
    }

    private static void thread$$setDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> { throw new IllegalStateException(throwable); });
    }

    private static void localeServiceProvider$() {
        new DummyLocaleServiceProvider();
    }

    private static void breakIteratorProvider$() {
        new DummyBreakIteratorProvider();
    }

    private static void collatorProvider$() {
        new DummyCollatorProvider();
    }

    private static void dateFormatProvider$() {
        new DummyDateFormatProvider();
    }

    private static void dateFormatSymbolsProvider$() {
        new DummyDateFormatSymbolsProvider();
    }

    private static void decimalFormatSymbolsProvider$() {
        new DummyDecimalFormatSymbolsProvider();
    }

    private static void numberFormatProvider$() {
        new DummyNumberFormatProvider();
    }

    private static void calendarDataProvider$() {
        new DummyCalendarDataProvider();
    }

    private static void calendarNameProvider$() {
        new DummyCalendarNameProvider();
    }

    private static void currencyNameProvider$() {
        new DummyCurrencyNameProvider();
    }

    private static void localeNameProvider$() {
        new DummyLocaleNameProvider();
    }

    private static void timeZoneNameProvider$() {
        new DummyTimeZoneNameProvider();
    }

    private static void logManager$() {
    }

    private static void datagramSocket$$setDatagramSocketImplFactory() {
    }

    private static void httpURLConnection$$setFollowRedirects() {
    }

    private static void serverSocket$$setSocketFactory() {
    }

    private static void socket$$setSocketImplFactory() {
    }

    private static void url$$setURLStreamHandlerFactory() {
    }

    private static void urlConnection$$setFileNameMap() {
    }

    private static void urlConnection$$setContentHandlerFactory() {
    }


    public RestEntitlementsCheckAction(String prefix) {
        this.prefix = prefix;
    }

    public static Set<String> getCheckActionsAllowedInPlugins() {
        return checkActions.entrySet()
            .stream()
            .filter(kv -> kv.getValue().isAlwaysDeniedToPlugins() == false)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    public static Set<String> getAllCheckActions() {
        return checkActions.keySet();
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, "/_entitlement/" + prefix + "/_check"));
    }

    @Override
    public String getName() {
        return "check_" + prefix + "_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        logger.info("RestEntitlementsCheckAction rest handler [{}]", request.path());
        var actionName = request.param("action");
        if (Strings.isNullOrEmpty(actionName)) {
            throw new IllegalArgumentException("Missing action parameter");
        }
        var checkAction = checkActions.get(actionName);
        if (checkAction == null) {
            throw new IllegalArgumentException(Strings.format("Unknown action [%s]", actionName));
        }

        return channel -> {
            logger.info("Calling check action [{}]", actionName);
            checkAction.action().run();
            channel.sendResponse(new RestResponse(RestStatus.OK, Strings.format("Succesfully executed action [%s]", actionName)));
        };
    }
}
