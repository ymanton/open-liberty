/*******************************************************************************
 * Copyright (c) 2014, 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ejbcontainer.interceptor.v32.aroundconstruct.xml.ejb;

//@Stateless
//@Interceptors(AroundConstructInterceptor.class)
public class TestUncheckedExceptionErrorBeanPostConstruct {

    public TestUncheckedExceptionErrorBeanPostConstruct() {}

    //@PostConstruct
    public void postConstruct() throws TestRuntimeException {
        throw new TestRuntimeException();
    }

    public void method() {}
}
