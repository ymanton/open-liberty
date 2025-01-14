/*******************************************************************************
 * Copyright (c) 2014, 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.classloading;

/**
 * Services should implement this interface to allow application class loaders to delegate to them.
 */
public interface ClassProvider {
    /** @return the class loader to delegate to */
    <CL extends ClassLoader & LibertyClassLoader> CL getDelegateLoader();
}
