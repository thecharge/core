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

package com.dcentralized.core.services.common;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.dcentralized.core.common.FactoryService;
import com.dcentralized.core.common.Operation;
import com.dcentralized.core.common.Service;
import com.dcentralized.core.common.ServiceStats.ServiceStat;
import com.dcentralized.core.common.ServiceSubscriptionState.ServiceSubscriber;
import com.dcentralized.core.common.TaskState;
import com.dcentralized.core.common.TaskState.TaskStage;
import com.dcentralized.core.common.Utils;
import com.dcentralized.core.services.common.TaskService.TaskServiceState;

/**
 * Default implementation of a task factory service that handles indirect to direct task
 * processing. The factory will special case a POST request to create a child task, that has
 * taskInfo.isDirect=true. Using a subscription, it will delay completion of the POST,
 * and only complete it when it receives a notification that the child task has reached a final
 * state
 */
public class TaskFactoryService extends FactoryService {

    public static final String STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT = "subscriptionCount";

    public TaskFactoryService(Class<? extends TaskService.TaskServiceState> stateClass) {
        super(stateClass);
    }

    @SuppressWarnings("unchecked")
    public static FactoryService create(Class<? extends Service> childServiceType,
            ServiceOption... options) {
        try {
            Service s = childServiceType.newInstance();
            Class<? extends TaskService.TaskServiceState> childServiceDocumentType =
                    (Class<? extends TaskServiceState>) s.getStateType();
            FactoryService fs = new TaskFactoryService(childServiceDocumentType) {
                @Override
                public Service createServiceInstance() throws Throwable {
                    return childServiceType.newInstance();
                }
            };
            Arrays.stream(options).forEach(option -> fs.toggleOption(option, true));
            return fs;
        } catch (Exception e) {
            Utils.logWarning("Failure creating factory for %s: %s", childServiceType,
                    Utils.toString(e));
            return null;
        }

    }

    @Override
    public void handleRequest(Operation op, OperationProcessingStage opProcessingStage) {
        opProcessingStage = OperationProcessingStage.EXECUTING_SERVICE_HANDLER;

        boolean isIdempotentPut = (op.getAction() == Action.PUT) &&
                op.hasPragmaDirective(Operation.PRAGMA_DIRECTIVE_POST_TO_PUT);

        if (op.getAction() != Action.POST && !isIdempotentPut) {
            super.handleRequest(op, opProcessingStage);
            return;
        }

        if (!op.hasBody()) {
            op.fail(new IllegalArgumentException("body is required"));
            return;
        }

        TaskServiceState initState = (TaskServiceState) op.getBody(super.getStateType());

        if (initState.taskInfo == null || !initState.taskInfo.isDirect) {
            super.handleRequest(op, opProcessingStage);
            return;
        }

        // handle only direct request from a client, not forwarded or replicated requests, to avoid
        // duplicate processing
        if (op.isFromReplication() || op.isForwarded()) {
            super.handleRequest(op, opProcessingStage);
            return;
        }

        handleDirectTaskPost(op, initState);
    }

    private void handleDirectTaskPost(Operation post, TaskServiceState initState) {
        // Direct task handling. We want to keep the task service simple and unaware of the
        // pending POST from the client. This keeps the child task a true finite state machine that
        // can PATCH itself, etc
        if (initState.taskInfo.stage == null) {
            initState.taskInfo.stage = TaskStage.CREATED;
        }
        Operation clonedPost = post.clone();
        clonedPost.setCompletion((o, e) -> {
            if (e != null) {
                post.setStatusCode(o.getStatusCode())
                        .setBodyNoCloning(o.getBodyRaw())
                        .fail(e);
                return;
            }
            subscribeToChildTask(o, post);
        });

        clonedPost.setConnectionSharing(true);
        super.handleRequest(clonedPost, OperationProcessingStage.EXECUTING_SERVICE_HANDLER);
    }

    private void subscribeToChildTask(Operation o, Operation post) {

        // Its possible to unsubscribe multiple times given multiple notifications from a task
        // in a terminal state. We need to fix up our *approximate* accounting of active subscriptions
        ServiceStat st = getStat(STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT);
        if (st != null && st.latestValue < 0) {
            setStat(STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT, 0);
        }

        TaskServiceState initState = (TaskServiceState) o.getBody(super.getStateType());
        Operation subscribe = Operation.createPost(this, initState.documentSelfLink)
                .transferRefererFrom(post)
                .setCompletion((so, e) -> {
                    if (e == null) {
                        adjustStat(STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT, 1);
                        return;
                    }

                    post.setStatusCode(so.getStatusCode())
                            .setBodyNoCloning(so.getBodyRaw())
                            .fail(e);
                });

        AtomicBoolean taskComplete = new AtomicBoolean();
        long expiration = initState.documentExpirationTimeMicros;
        ServiceSubscriber sr = ServiceSubscriber.create(true).setUsePublicUri(true)
                .setReplayState(true)
                .setExpiration(expiration);
        Consumer<Operation> notifyC = (nOp) -> {
            nOp.complete();
            switch (nOp.getAction()) {
            case PUT:
            case PATCH:
                TaskServiceState task = (TaskServiceState) nOp.getBody(super.getStateType());
                if (task.taskInfo == null || TaskState.isInProgress(task.taskInfo)) {
                    return;
                }
                if (taskComplete.compareAndSet(false, true)) {
                    // task is in final state (failed, or completed), complete original post
                    post.setBodyNoCloning(task).complete();
                    stopInDirectTaskSubscription(subscribe, nOp.getUri());
                }
                return;
            case DELETE:
                if (Utils.getSystemNowMicrosUtc() >= expiration) {
                    // the task might have expired and self deleted, fail the client post
                    post.setStatusCode(Operation.STATUS_CODE_TIMEOUT)
                            .fail(new IllegalStateException("Task expired"));
                    stopInDirectTaskSubscription(subscribe, nOp.getUri());
                } else {
                    // this is a self DELETE, the task is done
                    post.complete();
                    // subscription stop will happen on the PATCH/PUT for the terminal state
                }

                return;
            default:
                break;

            }
        };


        // Only if this is an owner-selected service, we create a reliable subscription.
        // Otherwise for non-replicated services, we just create a normal subscription.
        if (this.hasChildOption(ServiceOption.OWNER_SELECTION)) {
            ReliableSubscriptionService notificationTarget = ReliableSubscriptionService.create(
                    subscribe, sr, notifyC);
            getHost().startSubscriptionService(subscribe, notificationTarget, sr);
        } else {
            getHost().startSubscriptionService(subscribe, notifyC, sr);
        }
    }

    private void stopInDirectTaskSubscription(Operation sub, URI notificationTarget) {
        if (getHost().getServiceStage(notificationTarget.getPath()) != ProcessingStage.AVAILABLE) {
            adjustStat(STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT, -1);
            return;
        }
        getHost().stopSubscriptionService(
                sub.clone().setAction(Action.DELETE).setCompletion((o, e) -> {
                    if (e != null) {
                        return;
                    }
                    adjustStat(STAT_NAME_ACTIVE_SUBSCRIPTION_COUNT, -1);
                }), notificationTarget);
    }

    @Override
    public Service createServiceInstance() throws Throwable {
        return null;
    }

}