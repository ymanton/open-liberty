/*******************************************************************************
 * Copyright (c) 2012, 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.javaee.ddmodel.ejb;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.ibm.ws.javaee.dd.common.InterceptorCallback;
import com.ibm.ws.javaee.dd.ejb.ActivationConfig;
import com.ibm.ws.javaee.dd.ejb.ActivationConfigProperty;
import com.ibm.ws.javaee.dd.ejb.EJBJar;
import com.ibm.ws.javaee.dd.ejb.EnterpriseBean;
import com.ibm.ws.javaee.dd.ejb.MessageDriven;
import com.ibm.ws.javaee.dd.ejb.NamedMethod;
import com.ibm.ws.javaee.dd.ejb.Timer;
import com.ibm.ws.javaee.dd.ejb.TransactionalBean;

@RunWith(Parameterized.class)
public class EJBJarMDBeanTest extends EJBJarTestBase {
    @Parameters
    public static Iterable<? extends Object> data() {
        return TEST_DATA;
    }
    
    public EJBJarMDBeanTest(boolean ejbInWar) {
        super(ejbInWar);
    }

    //

    protected static final String mdbXML =
        "<enterprise-beans>" +
            "<message-driven>" +
                "<ejb-name>ejbName0</ejb-name>" +
            "</message-driven>" +

            "<message-driven>" +
                "<ejb-name>ejbName1</ejb-name>" +
                "<ejb-class>ejbClass1</ejb-class>" +
                "<mapped-name>mappedName1</mapped-name>" +
                "<messaging-type>messagingType1</messaging-type>" +
                "<message-destination-type>messageDestinationType0</message-destination-type>" +
                "<message-destination-link>messageDestinationLink0</message-destination-link>" +
            "</message-driven>" +
        "</enterprise-beans>";
    
    @Test
    public void testMessageDrivenBeanMethods() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar20(mdbXML) ).getEnterpriseBeans();
        Assert.assertEquals(2, beans.size());

        MessageDriven bean0 = (MessageDriven) beans.get(0);
        Assert.assertEquals(EnterpriseBean.KIND_MESSAGE_DRIVEN, bean0.getKindValue());
        Assert.assertEquals("ejbName0", bean0.getName());
        Assert.assertEquals(null, bean0.getEjbClassName());
        Assert.assertEquals(null, bean0.getMappedName());
        Assert.assertEquals(null, bean0.getMessagingTypeName());
        Assert.assertEquals(null, bean0.getMessageDestinationName());
        Assert.assertEquals(null, bean0.getLink());

        MessageDriven bean1 = (MessageDriven) beans.get(1);
        Assert.assertEquals("ejbName1", bean1.getName());
        Assert.assertEquals("ejbClass1", bean1.getEjbClassName());
        Assert.assertEquals("mappedName1", bean1.getMappedName());
        Assert.assertEquals("messagingType1", bean1.getMessagingTypeName());
        Assert.assertEquals("messageDestinationType0", bean1.getMessageDestinationName());
        Assert.assertEquals("messageDestinationLink0", bean1.getLink());
    }

    // V0 has no 'destination-type', which means there is no provisioning dependency.

    protected static final String mdbMessageDriven0XML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                "</message-driven>" +

                "<message-driven>" +
                    "<ejb-name>ejbName1</ejb-name>" +
                    "<activation-config>" +
                        "<activation-config-property>" +
                            "<activation-config-property-name>name0</activation-config-property-name>" +
                            "<activation-config-property-value>value0</activation-config-property-value>" +
                        "</activation-config-property>" +
                        "<activation-config-property>" +
                            "<activation-config-property-name>name1</activation-config-property-name>" +
                            "<activation-config-property-value>value1</activation-config-property-value>" +
                        "</activation-config-property>" +
                        "<activation-config-property>" +
                            "<activation-config-property-name>name2</activation-config-property-name>" +
                            "<activation-config-property-value>value2</activation-config-property-value>" +
                        "</activation-config-property>" +
                    "</activation-config>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenActivationConfig_V0_AtMax() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar21(mdbMessageDriven0XML) )
            .getEnterpriseBeans();
        validateMDBs_V0(beans);
    }
    
    @Test
    public void testMessageDrivenActivationConfig_V0_At21() throws Exception {
        List<EnterpriseBean> beans = parseEJBJar( ejbJar21(mdbMessageDriven0XML), EJBJar.VERSION_2_1 )
            .getEnterpriseBeans();
        validateMDBs_V0(beans);
    }
    
    @Test
    public void testMessageDrivenActivationConfig_V0_At40() throws Exception {
        List<EnterpriseBean> beans = parseEJBJar( ejbJar21(mdbMessageDriven0XML), EJBJar.VERSION_4_0 )
                .getEnterpriseBeans();
        validateMDBs_V0(beans);
    }    

    protected void validateMDBs_V0(List<EnterpriseBean> beans) throws Exception {
        MessageDriven bean0 = (MessageDriven) beans.get(0);
        Assert.assertEquals(EnterpriseBean.KIND_MESSAGE_DRIVEN, bean0.getKindValue());
        Assert.assertEquals(null, bean0.getActivationConfigValue());

        MessageDriven bean1 = (MessageDriven) beans.get(1);
        ActivationConfig actCon0 = bean1.getActivationConfigValue();
        List<ActivationConfigProperty> actConProps = actCon0.getConfigProperties();
        Assert.assertEquals("name0", actConProps.get(0).getName());
        Assert.assertEquals("value0", actConProps.get(0).getValue());
        Assert.assertEquals("name1", actConProps.get(1).getName());
        Assert.assertEquals("value1", actConProps.get(1).getValue());
        Assert.assertEquals("name2", actConProps.get(2).getName());
        Assert.assertEquals("value2", actConProps.get(2).getValue());
    }

    // V0 uses 'destination-type', which is a provisioning dependency.
    
    protected static final String mdbMessageDriven1XML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                    "<message-selector>messageSelector0</message-selector>" +
                    "<acknowledge-mode>Auto-acknowledge</acknowledge-mode>" +
                    "<message-driven-destination id=\"tid\">" +
                        "<destination-type>javax.jms.Queue</destination-type>" +
                        "<subscription-durability>Durable</subscription-durability>" +
                    "</message-driven-destination>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenActivationConfig_V1_Max() throws Exception {
        parseEJBJarMax( ejbJar20(mdbMessageDriven1XML), "CWWKC2273E", "invalid.enum.value"); 
    }
    
    @Test
    public void testMessageDrivenActivationConfig_V1_At21() throws Exception {
        List<EnterpriseBean> beans =
            parseEJBJar( ejbJar20(mdbMessageDriven1XML), EJBJar.VERSION_2_1)
                .getEnterpriseBeans();

        validateMDBs_V1(beans);
    }
    
    @Test
    public void testMessageDrivenActivationConfig_V1_At40() throws Exception {
        parseEJBJar( ejbJar20(mdbMessageDriven1XML),
                     EJBJar.VERSION_4_0,
                     "CWWKC2273E", "invalid.enum.value"); 
    }

    protected void validateMDBs_V1(List<EnterpriseBean> beans) throws Exception {
        MessageDriven bean0 = (MessageDriven) beans.get(0);
        ActivationConfig actCon0 = bean0.getActivationConfigValue();
        List<ActivationConfigProperty> actConProps = actCon0.getConfigProperties();
        Assert.assertEquals(actConProps.toString(), 4, actConProps.size());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_MESSAGE_SELECTOR, actConProps.get(0).getName());
        Assert.assertEquals("messageSelector0", actConProps.get(0).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_ACKNOWLEDGE_MODE, actConProps.get(1).getName());
        Assert.assertEquals("Auto-acknowledge", actConProps.get(1).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_DESTINATION_TYPE, actConProps.get(2).getName());
        Assert.assertEquals("javax.jms.Queue", actConProps.get(2).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_SUBSCRIPTION_DURABILITY, actConProps.get(3).getName());
        Assert.assertEquals("Durable", actConProps.get(3).getValue());
    }

    protected static final String mdbMessageDriven2XML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                    "<message-selector>messageSelector0</message-selector>" +
                    "<acknowledge-mode>Auto-acknowledge</acknowledge-mode>" +
                    "<message-driven-destination id=\"tid\">" +
                    "<destination-type>jakarta.jms.Queue</destination-type>" +
                    "<subscription-durability>Durable</subscription-durability>" +
                    "</message-driven-destination>" +
                "</message-driven>" +
            "</enterprise-beans>";
    
    @Test
    public void testMessageDrivenActivationConfig_V2_Max() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar20(mdbMessageDriven2XML) )
            .getEnterpriseBeans();
        validateMDBs_V2(beans);
    }

    @Test
    public void testMessageDrivenActivationConfig_V2_At21() throws Exception {
        parseEJBJar( ejbJar20(mdbMessageDriven2XML),
                     EJBJar.VERSION_2_1,
                     "CWWKC2273E", "invalid.enum.value"); 
    }

    protected void validateMDBs_V2(List<EnterpriseBean> beans) throws Exception {    
        MessageDriven bean0 = (MessageDriven) beans.get(0);
        ActivationConfig actCon0 = bean0.getActivationConfigValue();
        List<ActivationConfigProperty> actConProps = actCon0.getConfigProperties();
        Assert.assertEquals(actConProps.toString(), 4, actConProps.size());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_MESSAGE_SELECTOR, actConProps.get(0).getName());
        Assert.assertEquals("messageSelector0", actConProps.get(0).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_ACKNOWLEDGE_MODE, actConProps.get(1).getName());
        Assert.assertEquals("Auto-acknowledge", actConProps.get(1).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_DESTINATION_TYPE, actConProps.get(2).getName());
        Assert.assertEquals("jakarta.jms.Queue", actConProps.get(2).getValue());
        Assert.assertEquals(MessageDriven.ACTIVATION_CONFIG_PROPERTY_SUBSCRIPTION_DURABILITY, actConProps.get(3).getName());
        Assert.assertEquals("Durable", actConProps.get(3).getValue());
    }

    protected static final String mdbMessageDriven3XML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                    "<message-selector>messageSelector0</message-selector>" +
                    "<acknowledge-mode>Auto-acknowledge</acknowledge-mode>" +
                    "<message-driven-destination id=\"tid\">" +
                    "<destination-type>javax.jms.Queue</destination-type>" +
                    "<subscription-durability>Durable</subscription-durability>" +
                    "</message-driven-destination>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenActivationConfigEJB20Exception() throws Exception {
        parseEJBJarMax( ejbJar20(mdbMessageDriven3XML),
                        "CWWKC2273E", "invalid.enum.value");
    }

    protected static final String mdbTimeoutXML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                "</message-driven>" +

                "<message-driven>" +
                    "<ejb-name>ejbName1</ejb-name>" +

                    "<timeout-method>" +
                        "<method-name>methodName0</method-name>" +
                        "<method-params>" +
                            "<method-param>methParam0</method-param>" +
                            "<method-param>methParam1</method-param>" +
                            "<method-param>methParam2</method-param>" +
                        "</method-params>" +
                    "</timeout-method>" +

                    "<timer>" +
                        "<start>start0</start>" +
                        "<end>end0</end>" +
                        "<timeout-method>" +
                            "<method-name>timerTimeoutMethod0</method-name>" +
                        "</timeout-method>" +
                    "</timer>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenTimeOutServiceBean() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar20(mdbTimeoutXML) ).getEnterpriseBeans();

        MessageDriven bean0 = (MessageDriven) beans.get(0);
        Assert.assertEquals(EnterpriseBean.KIND_MESSAGE_DRIVEN, bean0.getKindValue());
        Assert.assertEquals(null, bean0.getTimeoutMethod());
        Assert.assertEquals(true, bean0.getTimers().isEmpty());

        MessageDriven bean1 = (MessageDriven) beans.get(1);
        NamedMethod namedMeth0 = bean1.getTimeoutMethod();
        Assert.assertEquals("methodName0", namedMeth0.getMethodName());
        Assert.assertEquals("methParam0", namedMeth0.getMethodParamList().get(0));
        Assert.assertEquals("methParam1", namedMeth0.getMethodParamList().get(1));
        Assert.assertEquals("methParam2", namedMeth0.getMethodParamList().get(2));

        Timer timer0 = bean1.getTimers().get(0);
        Assert.assertEquals("start0", timer0.getStart());
        Assert.assertEquals("end0", timer0.getEnd());
        Assert.assertEquals("timerTimeoutMethod0", timer0.getTimeoutMethod().getMethodName());
    }

    protected static final String mdbTransactionalXML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                "</message-driven>" +
                    
                "<message-driven>" +
                    "<ejb-name>ejbName1</ejb-name>" +
                    "<transaction-type>Bean</transaction-type>" +
                "</message-driven>" +
                    
                "<message-driven>" +
                    "<ejb-name>ejbName2</ejb-name>" +
                    "<transaction-type>Container</transaction-type>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenTransactionalBean() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar20(mdbTransactionalXML) ).getEnterpriseBeans();

        MessageDriven mdb0 = (MessageDriven) beans.get(0);
        MessageDriven mdb1 = (MessageDriven) beans.get(1);
        MessageDriven mdb2 = (MessageDriven) beans.get(2);

        Assert.assertEquals(TransactionalBean.TRANSACTION_TYPE_UNSPECIFIED, mdb0.getTransactionTypeValue());
        Assert.assertEquals(TransactionalBean.TRANSACTION_TYPE_BEAN, mdb1.getTransactionTypeValue());
        Assert.assertEquals(TransactionalBean.TRANSACTION_TYPE_CONTAINER, mdb2.getTransactionTypeValue());
    }

    protected static final String mdbInterceptorXML =
            "<enterprise-beans>" +
                "<message-driven>" +
                    "<ejb-name>ejbName0</ejb-name>" +
                "</message-driven>" +
                    
                "<message-driven>" +
                    "<ejb-name>ejbName1</ejb-name>" +
                    "<around-invoke>" +
                        "<method-name>aroundInvokeMethodName0</method-name>" +
                        "<class>aroundInvokeClass0</class>" +
                    "</around-invoke>" +
                    "<around-invoke>" +
                        "<method-name>aroundInvokeMethodName1</method-name>" +
                    "</around-invoke>" +
                    "<around-timeout>" +
                        "<method-name>aroundTimeoutMethodName0</method-name>" +
                        "<class>aroundTimeoutClass0</class>" +
                    "</around-timeout>" +
                "</message-driven>" +
            "</enterprise-beans>";

    @Test
    public void testMessageDrivenMethodInterceptorBean() throws Exception {
        List<EnterpriseBean> beans = parseEJBJarMax( ejbJar20(mdbInterceptorXML) ).getEnterpriseBeans();

        MessageDriven mdb0 = (MessageDriven) beans.get(0);
        MessageDriven mdb1 = (MessageDriven) beans.get(1);
        Assert.assertEquals(true, mdb0.getAroundInvoke().isEmpty());
        Assert.assertEquals(true, mdb0.getAroundTimeoutMethods().isEmpty());

        InterceptorCallback intCallback0 = mdb1.getAroundInvoke().get(0);
        Assert.assertEquals("aroundInvokeClass0", intCallback0.getClassName());
        Assert.assertEquals("aroundInvokeMethodName0", intCallback0.getMethodName());
        InterceptorCallback intCallback1 = mdb1.getAroundInvoke().get(1);
        Assert.assertEquals(null, intCallback1.getClassName());
        Assert.assertEquals("aroundInvokeMethodName1", intCallback1.getMethodName());

        InterceptorCallback intCallback2 = mdb1.getAroundTimeoutMethods().get(0);
        Assert.assertEquals("aroundTimeoutClass0", intCallback2.getClassName());
        Assert.assertEquals("aroundTimeoutMethodName0", intCallback2.getMethodName());
    }
}
