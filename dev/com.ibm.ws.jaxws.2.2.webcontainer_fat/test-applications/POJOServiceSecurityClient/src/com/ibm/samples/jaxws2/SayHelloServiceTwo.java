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
package com.ibm.samples.jaxws2;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.ws.Service;
import javax.xml.ws.WebEndpoint;
import javax.xml.ws.WebServiceClient;
import javax.xml.ws.WebServiceFeature;

/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.1.6 in JDK 6
 * Generated source version: 2.1
<<<<<<< HEAD
 *
 */
@WebServiceClient(name = "SayHelloServiceTwo", targetNamespace = "http://jaxws2.samples.ibm.com", wsdlLocation = "META-INF/resources/wsdl/SayHelloServiceTwo.wsdl")
public class SayHelloServiceTwo extends Service {
=======
 * 
 */
@WebServiceClient(name = "SayHelloServiceTwo", targetNamespace = "http://jaxws2.samples.ibm.com", wsdlLocation = "META-INF/resources/wsdl/SayHelloServiceTwo.wsdl")
public class SayHelloServiceTwo
                extends Service
{
>>>>>>> f8f25ee2d3... SOAPAction and test fixes

    private final static URL SAYHELLOSERVICETWO_WSDL_LOCATION;
    private final static Logger logger = Logger.getLogger(com.ibm.samples.jaxws2.SayHelloServiceTwo.class.getName());

    static {
        URL url = null;
        try {
            URL baseUrl;
            baseUrl = com.ibm.samples.jaxws2.SayHelloServiceTwo.class.getResource(".");
            url = new URL(baseUrl, "SayHelloServiceTwo.wsdl");
        } catch (MalformedURLException e) {
            logger.warning("Failed to create URL for the wsdl Location: 'SayHelloServiceTwo.wsdl', retrying as a local file");
            logger.warning(e.getMessage());
        }
        SAYHELLOSERVICETWO_WSDL_LOCATION = url;
    }

    public SayHelloServiceTwo(URL wsdlLocation, QName serviceName) {
        super(wsdlLocation, serviceName);
    }

    public SayHelloServiceTwo() {
        super(SAYHELLOSERVICETWO_WSDL_LOCATION, new QName("http://jaxws2.samples.ibm.com", "SayHelloServiceTwo"));
    }

    /**
<<<<<<< HEAD
     *
=======
     * 
>>>>>>> f8f25ee2d3... SOAPAction and test fixes
     * @return
     *         returns SayHello
     */
    @WebEndpoint(name = "SayHelloImplTwoPort")
    public SayHello getSayHelloImplTwoPort() {
        return super.getPort(new QName("http://jaxws2.samples.ibm.com", "SayHelloImplTwoPort"), SayHello.class);
    }

    /**
<<<<<<< HEAD
     *
     * @param features
     *                     A list of {@link javax.xml.ws.WebServiceFeature} to configure on the proxy. Supported features not in the <code>features</code> parameter will have their
     *                     default
     *                     values.
=======
     * 
     * @param features
     *            A list of {@link javax.xml.ws.WebServiceFeature} to configure on the proxy. Supported features not in the <code>features</code> parameter will have their default
     *            values.
>>>>>>> f8f25ee2d3... SOAPAction and test fixes
     * @return
     *         returns SayHello
     */
    @WebEndpoint(name = "SayHelloImplTwoPort")
    public SayHello getSayHelloImplTwoPort(WebServiceFeature... features) {
        return super.getPort(new QName("http://jaxws2.samples.ibm.com", "SayHelloImplTwoPort"), SayHello.class, features);
    }

}
