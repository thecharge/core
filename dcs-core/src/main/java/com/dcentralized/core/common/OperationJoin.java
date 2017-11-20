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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The {@link OperationJoin} construct is a handler for {@link OperationJoin#create(Operation...)}
 * functionality. After multiple parallel requests have
 * completed, only then will invoked all {@link com.vmware.xenon.common.Operation.CompletionHandler}s providing all operations and
 * failures as part of the execution context.
 */
public class OperationJoin {
    private static final int APPROXIMATE_EXPECTED_CAPACITY = 4;
    public static final String ERROR_MSG_BATCH_LIMIT_VIOLATED = "batch limit violated";
    public static final String ERROR_MSG_INVALID_BATCH_SIZE = "batch size must be greater than 0";
    public static final String ERROR_MSG_OPERATIONS_ALREADY_SET = "operations have already been set";
    private final ConcurrentHashMap<Long, Operation> operations;
    private ConcurrentHashMap<Long, Throwable> failures;
    JoinedCompletionHandler joinedCompletion;
    private Operation.AuthorizationContext authContext;
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger batchSizeGuard = new AtomicInteger();
    private int batchSize = 0;
    private Iterator<Operation> operationIterator;
    private ServiceRequestSender sender;
    private final Object failuresLock = new Object();

    private OperationJoin() {
        this.operations = new ConcurrentHashMap<>(APPROXIMATE_EXPECTED_CAPACITY);
        this.authContext = OperationContext.getAuthorizationContext();
    }

    /**
     * Create {@link OperationJoin} with no operations in preparation for {@link Operation}s to
     * be added at a later time.
     */
    public static OperationJoin create() {
        return new OperationJoin();
    }

    /**
     * Create {@link OperationJoin} with an array of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Operation... ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Create {@link OperationJoin} with a collection of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Collection<Operation> ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Create {@link OperationJoin} with a stream of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Stream<Operation> ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Operation... ops) {
        if (ops.length == 0) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        for (Operation op : ops) {
            prepareOperation(op);
        }

        this.operationIterator = this.operations.values().iterator();
        return this;
    }

    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Collection<Operation> ops) {
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        for (Operation op : ops) {
            prepareOperation(op);
        }

        this.operationIterator = this.operations.values().iterator();
        return this;
    }



    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Stream<Operation> ops) {
        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        ops.forEach(this::prepareOperation);
        this.operationIterator = this.operations.values().iterator();

        if (isEmpty()) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        return this;
    }

    private void prepareOperation(Operation op) {
        this.operations.put(op.getId(), op);

        op.nestCompletion(this::parentCompletion);
        this.pendingCount.incrementAndGet();
    }

    private void parentCompletion(Operation o, Throwable e) {
        if (e != null) {
            synchronized (this.failuresLock) {
                if (this.failures == null) {
                    this.failures = new ConcurrentHashMap<>();
                }
            }
            this.failures.put(o.getId(), e);
        }

        Operation originalOp = getOperation(o.getId());
        originalOp.setStatusCode(o.getStatusCode())
                .transferResponseHeadersFrom(o)
                .setBodyNoCloning(o.getBodyRaw());

        this.batchSizeGuard.decrementAndGet();
        sendNext();

        if (this.pendingCount.decrementAndGet() != 0) {
            return;
        }

        OperationContext.restoreAuthContext(this.authContext);
        // call each operation completion individually
        for (Operation op : this.operations.values()) {
            Throwable t = null;
            if (this.failures != null) {
                t = this.failures.get(op.getId());
            }
            if (t != null) {
                op.fail(t);
            } else {
                op.complete();
            }
        }

        if (this.joinedCompletion != null) {
            this.joinedCompletion.handle(this.operations, this.failures);
        }
    }

    private void sendWithBatch() {
        if (this.operationIterator == null || !this.operationIterator.hasNext()) {
            throw new IllegalStateException("No operations to be sent");
        }

        // Move the operations to local list to avoid concurrency issues with iterator
        // when sendNext could be called from handler of returning operation
        // before we get out of this method.
        ArrayList<Operation> batch = new ArrayList<>();
        int count = 0;
        while (this.operationIterator.hasNext()) {
            batch.add(this.operationIterator.next());
            count++;
            if (this.batchSize > 0 && count == this.batchSize) {
                break;
            }
        }

        for (Operation op : batch) {
            sendOperation(op);
            if (this.batchSize > 0 && this.batchSizeGuard.incrementAndGet() > this.batchSize) {
                throw new IllegalStateException((ERROR_MSG_BATCH_LIMIT_VIOLATED));
            }
        }
    }

    private void sendOperation(Operation op) {
        this.sender.sendRequest(op);
    }

    private void sendNext() {
        if (this.sender == null) {
            return;
        }

        Operation op = null;
        synchronized (this.operationIterator) {
            if (this.operationIterator.hasNext()) {
                op = this.operationIterator.next();
            }
        }

        if (op != null) {
            if (this.batchSize > 0 && this.batchSizeGuard.incrementAndGet() > this.batchSize) {
                throw new IllegalStateException((ERROR_MSG_BATCH_LIMIT_VIOLATED));
            }
            sendOperation(op);
        }
    }

    /**
     * Send the join operations using the {@link ServiceRequestSender}. A sender can be
     * a ServiceHost, ServiceClient or a Service.
     * Caller can also provide batch size to control the rate at which operations are sent.
     */
    public void sendWith(ServiceRequestSender sender, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(ERROR_MSG_INVALID_BATCH_SIZE);
        }

        this.batchSize = batchSize;
        this.sendWith(sender);
    }

    /**
     * Send the join operations using the {@link ServiceHost}.
     */
    public void sendWith(ServiceRequestSender sender) {
        if (sender == null) {
            throw new IllegalArgumentException("host must not be null.");
        }

        this.sender = sender;
        sendWithBatch();
    }

    public OperationJoin setCompletion(JoinedCompletionHandler joinedCompletion) {
        this.joinedCompletion = joinedCompletion;
        return this;
    }

    public boolean isEmpty() {
        return this.operations.isEmpty();
    }

    public Collection<Operation> getOperations() {
        return this.operations.values();
    }

    public Map<Long, Throwable> getFailures() {
        return this.failures;
    }

    public Operation getOperation(long id) {
        return this.operations.get(id);
    }

    @FunctionalInterface
    public interface JoinedCompletionHandler {
        void handle(Map<Long, Operation> ops, Map<Long, Throwable> failures);
    }


    /**
     * WARNING: This method is unsafe. If called when some operation o in this.operations has been
     * sent but neither completed nor failed, o will be failed immediately, which may cause immediate
     * success/fail of all operations in this.operations (if it was the last pending operation due to the latch in
     * parentCompletion), and separately the actual outstanding async work will when it calls complete or fail, cause
     * the original callback to be called, potentially succeeding even though this method was intended to fail
     * everything.
     *
     * @param t The exception to fail operations with.
     */
    void fail(Throwable t) {
        this.failures = new ConcurrentHashMap<>();
        this.failures.put(this.operations.keys().nextElement(), t);
        Operation.AuthorizationContext origContext = OperationContext.getAuthorizationContext();
        OperationContext.restoreAuthContext(this.authContext);
        for (Operation op : this.operations.values()) {
            op.fail(t);
        }
        OperationContext.restoreAuthContext(origContext);
    }
}
