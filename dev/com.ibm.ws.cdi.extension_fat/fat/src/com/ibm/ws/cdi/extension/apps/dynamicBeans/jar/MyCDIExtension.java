/*******************************************************************************
 * Copyright (c) 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.cdi.extension.apps.dynamicBeans.jar;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Extension;

/**
 *
 */
public class MyCDIExtension implements Extension {

    public void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
        DynamicBean1Bean bean1 = new DynamicBean1Bean();
        event.addBean(bean1);
        DynamicBean2Bean bean2 = new DynamicBean2Bean();
        event.addBean(bean2);
    }

}
