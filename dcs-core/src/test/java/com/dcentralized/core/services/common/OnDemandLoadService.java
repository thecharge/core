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

import com.dcentralized.core.common.Operation;
import com.dcentralized.core.common.ServiceDocument;
import com.dcentralized.core.common.StatefulService;
import com.dcentralized.core.common.Utils;
import com.dcentralized.core.common.test.ExampleService;

public class OnDemandLoadService extends StatefulService {

    public static final int MAX_STATE_SIZE = 1024 * 1024;

    public OnDemandLoadService() {
        super(ExampleService.ExampleServiceState.class);
    }

    @Override
    public void handleStart(Operation start) {
        if (!start.hasBody()) {
            start.fail(new IllegalArgumentException("body is required"));
            return;
        }
        start.complete();
    }

    @Override
    public void handlePatch(Operation op) {
        ExampleService.ExampleServiceState state = getState(op);
        ExampleService.ExampleServiceState body = getBody(op);
        Utils.mergeWithState(getStateDescription(), state, body);
        state.keyValues = body.keyValues;
        op.complete();
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ServiceDocument template = super.getDocumentTemplate();
        template.documentDescription.serializedStateSizeLimit = MAX_STATE_SIZE;
        return template;
    }
}
