/*
 * Copyright (c) 2014-2015 dCentralizedSystems, LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.dcentralized.core.common;

import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;

import com.dcentralized.core.common.NodeSelectorService.SelectOwnerResponse;
import com.dcentralized.core.common.Operation.CompletionHandler;
import com.dcentralized.core.common.Operation.OperationOption;
import com.dcentralized.core.common.OperationProcessingChain.Filter;
import com.dcentralized.core.common.OperationProcessingChain.FilterReturnCode;
import com.dcentralized.core.common.OperationProcessingChain.OperationProcessingContext;
import com.dcentralized.core.common.Service.ServiceOption;
import com.dcentralized.core.common.ServiceErrorResponse.ErrorDetail;

/**
 * This filter forwards the operation to the owner host, if needed.
 *
 * If the service is attached, it sticks it into the provided context for
 * subsequent filters to use.
 */
public class ForwardRequestFilter implements Filter {

    @Override
    public FilterReturnCode processRequest(Operation op, OperationProcessingContext context) {
        if (op.isFromReplication() || op.isForwarded() || op.isForwardingDisabled()) {
            // no need to forward
            return FilterReturnCode.CONTINUE_PROCESSING;
        }

        if (ServiceHost.isServiceStop(op)) {
            // this is a request to stop the local service instance - do not forward
            return FilterReturnCode.CONTINUE_PROCESSING;
        }

        String servicePath = op.getUri().getPath();
        if (servicePath == null) {
            // request with a null path - fail request
            Operation.failServiceNotFound(op);
            return FilterReturnCode.FAILED_STOP_PROCESSING;
        }

        // find service options (directly or, if needed - indirectly)
        Service service = context.getHost().findService(servicePath, false);
        Service parent = null;
        EnumSet<ServiceOption> options = null;
        if (service != null) {
            // Common path, service is known.
            context.setService(service);
            options = service.getOptions();

            if (options != null && options.contains(ServiceOption.UTILITY)) {
                // find the parent service, which will have the complete option set
                // relevant to forwarding
                servicePath = UriUtils.getParentPath(servicePath);
                parent = context.getHost().findService(servicePath, true);
                if (parent != null) {
                    options = parent.getOptions();
                }
            }
        } else {
            // Service is unknown.
            // Find the service options indirectly, if there is a parent factory.
            if (ServiceHost.isHelperServicePath(servicePath)) {
                servicePath = UriUtils.getParentPath(servicePath);
            }

            String factoryPath = UriUtils.getParentPath(servicePath);
            if (factoryPath != null) {
                parent = context.getHost().findService(factoryPath, true);
                if (parent != null) {
                    options = parent.getOptions();
                    if (parent instanceof FactoryService) {
                        FactoryService factory = (FactoryService) parent;
                        if (factory.hasChildOption(ServiceOption.OWNER_SELECTION)) {
                            options.add(ServiceOption.OWNER_SELECTION);
                        }
                        if (factory.hasChildOption(ServiceOption.REPLICATION)) {
                            options.add(ServiceOption.REPLICATION);
                        }
                        if (factory.hasChildOption(ServiceOption.PERSISTENCE)) {
                            options.add(ServiceOption.PERSISTENCE);
                        }
                    }
                }
            }
        }

        if (options == null) {
            // we could not find service options directly nor indirectly - do not forward
            return FilterReturnCode.CONTINUE_PROCESSING;
        }

        if (service != null && (!options.contains(ServiceOption.OWNER_SELECTION) ||
                options.contains(ServiceOption.FACTORY))) {
            // service is known but it doesn't have OWNER_SELECTION - do not forward
            return FilterReturnCode.CONTINUE_PROCESSING;
        }

        if (service == null && (!options.contains(ServiceOption.FACTORY) ||
                !(options.contains(ServiceOption.REPLICATION) ||
                        options.contains(ServiceOption.OWNER_SELECTION)))) {
            // service is unknown and its parent is not a factory with REPLICATION or OWNER_SELECTION - do not forward
            return FilterReturnCode.CONTINUE_PROCESSING;
        }

        // request needs to be forwarded to owner
        final String finalServicePath = servicePath;
        final Service finalParent = parent;
        context.setSuspendConsumer(o -> {
            selectAndForwardRequestToOwner(service, finalServicePath, op, finalParent, context);
        });

        return FilterReturnCode.SUSPEND_PROCESSING;
    }

    private void selectAndForwardRequestToOwner(Service s, String path, Operation op,
            Service parent, OperationProcessingContext context) {
        String nodeSelectorPath = parent != null ? parent.getPeerNodeSelectorPath() :
                    s.getPeerNodeSelectorPath();

        ServiceHost host = context.getHost();
        CompletionHandler ch = (o, e) -> {
            if (e != null) {
                host.log(Level.SEVERE, "Owner selection failed for service %s, op %d. Error: %s", op
                        .getUri().getPath(), op.getId(), e.toString());
                context.resumeProcessingRequest(op, FilterReturnCode.FAILED_STOP_PROCESSING, e);
                op.setRetryCount(0).fail(e);
                return;
            }

            SelectOwnerResponse rsp = o.getBody(SelectOwnerResponse.class);
            if (rsp.isLocalHostOwner) {
                context.resumeProcessingRequest(op, FilterReturnCode.CONTINUE_PROCESSING, null);
            } else {
                forwardRequestToOwner(op, rsp, context);
            }
        };

        Operation selectOwnerOp = Operation
                .createPost(null)
                .setExpiration(op.getExpirationMicrosUtc())
                .setCompletion(ch);
        host.selectOwner(nodeSelectorPath, path, selectOwnerOp);
    }

    private void forwardRequestToOwner(Operation op,
            SelectOwnerResponse rsp, OperationProcessingContext context) {
        CompletionHandler fc = (fo, fe) -> {
            if (fe != null) {
                retryOrFailRequest(op, fo, fe, context);
                return;
            }

            op.setStatusCode(fo.getStatusCode());
            op.setBodyNoCloning(fo.getBodyRaw());

            op.setContentType(fo.getContentType());
            op.setContentLength(fo.getContentLength());
            op.transferResponseHeadersFrom(fo);

            context.resumeProcessingRequest(op, FilterReturnCode.SUCCESS_STOP_PROCESSING, null);
            op.complete();
        };

        Operation forwardOp = op.clone().setCompletion(fc);

        // Forwarded operations are retried until the parent operation, from the client,
        // expires. Since a peer might have become unresponsive, we want short time outs
        // and retries, to whatever peer we select, on each retry.
        ServiceHost host = context.getHost();
        forwardOp.setExpiration(Utils.fromNowMicrosUtc(
                 host.getOperationTimeoutMicros() / 10));
        forwardOp.setUri(SelectOwnerResponse.buildUriToOwner(rsp, op));

        prepareForwardRequest(forwardOp);
        host.sendRequest(forwardOp);
    }

    public static void prepareForwardRequest(Operation fwdOp) {
        fwdOp.toggleOption(OperationOption.FORWARDED, true);
        fwdOp.addPragmaDirective(Operation.PRAGMA_DIRECTIVE_FORWARDED);
        fwdOp.setConnectionTag(ServiceClient.CONNECTION_TAG_FORWARDING);
        if (NodeSelectorService.FORWARDING_OPERATION_OPTION != null) {
            fwdOp.toggleOption(NodeSelectorService.FORWARDING_OPERATION_OPTION, true);
        }
    }

    private void retryOrFailRequest(Operation op, Operation fo, Throwable fe,
            OperationProcessingContext context) {
        boolean shouldRetry = false;

        if (fo.hasBody()) {
            ServiceErrorResponse rsp = fo.clone().getBody(ServiceErrorResponse.class);
            if (rsp != null && rsp.details != null) {
                shouldRetry = rsp.details.contains(ErrorDetail.SHOULD_RETRY);
            }
        }

        if (fo.getStatusCode() == Operation.STATUS_CODE_TIMEOUT) {
            // the I/O code might have timed out, but we will keep retrying until the operation
            // expiration is reached
            shouldRetry = true;
        }

        if (op.hasPragmaDirective(Operation.PRAGMA_DIRECTIVE_FORWARDED)) {
            // only retry on the node the client directly communicates with. Any node that receives
            // a forwarded operation will have forwarding disabled set, and should not retry
            shouldRetry = false;
        }

        if (op.getExpirationMicrosUtc() < Utils.getSystemNowMicrosUtc()) {
            op.setBodyNoCloning(fo.getBodyRaw())
                    .fail(new CancellationException("Expired at " + op.getExpirationMicrosUtc()));
            return;
        }

        if (!shouldRetry) {
            context.resumeProcessingRequest(op, FilterReturnCode.FAILED_STOP_PROCESSING, fe);
            Operation.failForwardedRequest(op, fo, fe);
            return;
        }

        // We will report this as failure, for diagnostics purposes.
        // The retry mechanism starts a fresh processing of the operation.
        context.resumeProcessingRequest(op, FilterReturnCode.FAILED_STOP_PROCESSING, fe);
        context.getHost().getOperationTracker().trackOperationForRetry(Utils.getNowMicrosUtc(), fe, op);
    }
}
