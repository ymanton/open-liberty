<<<<<<< HEAD
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
=======
>>>>>>> f8f25ee2d3... SOAPAction and test fixes
package com.ibm.sample;

import javax.jws.WebService;

@WebService(name = "SayHello", targetNamespace = "http://jaxws2.samples.ibm.com")
public interface SayHelloInterface {

    public String sayHello(String name);

}
