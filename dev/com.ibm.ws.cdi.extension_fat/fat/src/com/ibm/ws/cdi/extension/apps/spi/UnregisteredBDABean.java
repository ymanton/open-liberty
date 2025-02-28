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
package com.ibm.ws.cdi.extension.apps.spi;

import com.ibm.ws.cdi.extension.spi.test.bundle.annotations.UnregisteredBDA;

@UnregisteredBDA
public class UnregisteredBDABean {

    @Override
    public String toString() {
        return "A class with a BDA that was not registerd through the SPI somehow got injected";
    }

}
