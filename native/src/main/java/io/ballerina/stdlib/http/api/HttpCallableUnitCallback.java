/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.ballerina.stdlib.http.api;

import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.observability.ObserveUtils;
import io.ballerina.runtime.observability.ObserverContext;
import io.ballerina.stdlib.http.api.nativeimpl.ModuleUtils;
import io.ballerina.stdlib.http.transport.message.HttpCarbonMessage;

import java.util.Locale;
import java.util.Objects;

import static io.ballerina.stdlib.http.api.HttpConstants.OBSERVABILITY_CONTEXT_PROPERTY;
import static java.lang.System.err;

/**
 * {@code HttpCallableUnitCallback} is the responsible for acting on notifications received from Ballerina side.
 *
 * @since 0.94
 */
public class HttpCallableUnitCallback implements Callback {
    private static final String ILLEGAL_FUNCTION_INVOKED = "illegal return: response has already been sent";

    private final BObject caller;
    private final Runtime runtime;
    private final String returnMediaType;
    private final BMap cacheConfig;
    private final HttpCarbonMessage requestMessage;
    private final BMap links;
    private final boolean isLastService;

    HttpCallableUnitCallback(HttpCarbonMessage requestMessage, Runtime runtime, HttpResource resource,
                             boolean isLastService) {
        this.requestMessage = requestMessage;
        this.runtime = runtime;
        this.returnMediaType = resource.getReturnMediaType();
        this.cacheConfig = resource.getResponseCacheConfig();
        this.links = resource.getLinks();
        String resourceAccessor = resource.getBalResource().getAccessor().toUpperCase(Locale.getDefault());
        this.caller = getCaller(requestMessage, resourceAccessor);
        this.isLastService = isLastService;
    }

    HttpCallableUnitCallback(HttpCarbonMessage requestMessage, Runtime runtime) {
        this.requestMessage = requestMessage;
        this.runtime = runtime;
        this.returnMediaType = null;
        this.cacheConfig = null;
        this.links = null;
        this.caller = getCaller(requestMessage, null);
        this.isLastService = false;
    }

    public Runtime getRuntime() {
        return this.runtime;
    }

    private BObject getCaller(HttpCarbonMessage requestMessage, String resourceAccessor) {
        BObject caller = requestMessage.getProperty(HttpConstants.CALLER) == null ?
                         ValueCreatorUtils.createCallerObject(requestMessage, resourceAccessor) :
                         (BObject) requestMessage.getProperty(HttpConstants.CALLER);
        caller.addNativeData(HttpConstants.TRANSPORT_MESSAGE, requestMessage);
        requestMessage.setProperty(HttpConstants.CALLER, caller);
        return caller;
    }

    @Override
    public void notifySuccess(Object result) {
        if (alreadyResponded(result)) {
            stopObserverContext();
            return;
        }
        if (result instanceof BError) {
            invokeErrorInterceptors((BError) result, true);
            return;
        }
        if (isLastService) {
            cleanupRequestMessage();
        }
        returnResponse(result);
    }

    private void returnResponse(Object result) {
        Object[] paramFeed = new Object[8];
        paramFeed[0] = result;
        paramFeed[1] = true;
        paramFeed[2] = Objects.nonNull(returnMediaType) ? StringUtils.fromString(returnMediaType) : null;
        paramFeed[3] = true;
        paramFeed[4] = cacheConfig;
        paramFeed[5] = true;
        paramFeed[6] = Objects.nonNull(links) && !links.isEmpty() ? links : null;
        paramFeed[7] = true;

        invokeBalMethod(paramFeed, "returnResponse");
    }

    private void returnErrorResponse(BError error) {
        Object[] paramFeed = new Object[4];
        paramFeed[0] = error;
        paramFeed[1] = true;
        paramFeed[2] = returnMediaType != null ? StringUtils.fromString(returnMediaType) : null;
        paramFeed[3] = true;

        invokeBalMethod(paramFeed, "returnErrorResponse");
    }

    public void invokeBalMethod(Object[] paramFeed, String methodName) {
        Callback returnCallback = new Callback() {
            @Override
            public void notifySuccess(Object result) {
                stopObserverContext();
                printStacktraceIfError(result);
            }

            @Override
            public void notifyFailure(BError result) {
                sendFailureResponse(result);
            }
        };
        runtime.invokeMethodAsyncSequentially(
                caller, methodName, null, ModuleUtils.getNotifySuccessMetaData(),
                returnCallback, null, PredefinedTypes.TYPE_NULL, paramFeed);
    }

    public void stopObserverContext() {
        if (ObserveUtils.isObservabilityEnabled()) {
            ObserverContext observerContext = (ObserverContext) requestMessage
                    .getProperty(OBSERVABILITY_CONTEXT_PROPERTY);
            if (observerContext != null && observerContext.isManuallyClosed()) {
                ObserveUtils.stopObservationWithContext(observerContext);
            }
        }
    }

    @Override
    public void notifyFailure(BError error) { // handles panic and check_panic
        // Allow the panics from internal authentication/authorization to be handled by the interceptors.
        if (error.getType().getName().equals(HttpErrorType.INTERNAL_LISTENER_AUTHN_ERROR.getErrorName())
                || error.getType().getName().equals(HttpErrorType.INTERNAL_LISTENER_AUTHZ_ERROR.getErrorName())) {
            invokeErrorInterceptors(error, true);
            return;
        }
        cleanupRequestMessage();
        sendFailureResponse(error);
        System.exit(1);
    }

    public void invokeErrorInterceptors(BError error, boolean printError) {
        requestMessage.setProperty(HttpConstants.INTERCEPTOR_SERVICE_ERROR, error);
        if (printError) {
            error.printStackTrace();
        }
        returnErrorResponse(error);
    }

    public void sendFailureResponse(BError error) {
        stopObserverContext();
        HttpUtil.handleFailure(requestMessage, error, true);
    }

    public void cleanupRequestMessage() {
        requestMessage.waitAndReleaseAllEntities();
    }

    private boolean alreadyResponded(Object result) {
        try {
            HttpUtil.methodInvocationCheck(requestMessage, HttpConstants.INVALID_STATUS_CODE, ILLEGAL_FUNCTION_INVOKED);
        } catch (BError e) {
            if (result != null) { // handles nil return and end of resource exec
                printStacktraceIfError(result);
                err.println(HttpConstants.HTTP_RUNTIME_WARNING_PREFIX + e.getMessage());
            }
            return true;
        }
        return false;
    }

    private void printStacktraceIfError(Object result) {
        if (result instanceof BError) {
            ((BError) result).printStackTrace();
        }
    }
}
