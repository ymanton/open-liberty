/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.context.timing;

import jakarta.enterprise.concurrent.spi.ThreadContextRestorer;
import jakarta.enterprise.concurrent.spi.ThreadContextSnapshot;

/**
 * Example third-party thread context provider, to be used for testing purposes.
 * This context associates a timestamp with a thread.
 */
public class TimestampContextSnapshot implements ThreadContextSnapshot {
    private final Long timestamp;

    TimestampContextSnapshot(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public ThreadContextRestorer begin() {
        ThreadContextRestorer restorer = new TimestampContextRestorer(Timestamp.local.get());
        Timestamp.local.set(timestamp);
        return restorer;
    }

    @Override
    public String toString() {
        return "TimestampContextSnapshot@" + Integer.toHexString(hashCode()) + ":" + timestamp;
    }
}