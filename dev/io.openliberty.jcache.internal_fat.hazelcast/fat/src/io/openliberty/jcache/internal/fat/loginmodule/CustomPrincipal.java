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
package io.openliberty.jcache.internal.fat.loginmodule;

import java.io.Serializable;
import java.security.Principal;

/**
 * Custom principal used to test serialization of Subjects to the authentication cache.
 */
public class CustomPrincipal implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return "CustomPrincipalName";
    }

    @Override
    public String toString() {
        return CustomPrincipal.class.getSimpleName();
    }
}
