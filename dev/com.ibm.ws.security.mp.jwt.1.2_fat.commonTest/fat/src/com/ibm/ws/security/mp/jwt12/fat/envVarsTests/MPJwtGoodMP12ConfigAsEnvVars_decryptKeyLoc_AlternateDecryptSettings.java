/*******************************************************************************
 * Copyright (c) 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.security.mp.jwt12.fat.envVarsTests;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.ws.security.fat.common.jwt.utils.JwtKeyTools;
import com.ibm.ws.security.fat.common.mp.jwt.MPJwt12FatConstants;
import com.ibm.ws.security.fat.common.mp.jwt.utils.MP12ConfigSettings;
import com.ibm.ws.security.mp.jwt12.fat.sharedTests.GenericEnvVarsAndSystemPropertiesTests;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.custom.junit.runner.Mode;
import componenttest.custom.junit.runner.Mode.TestMode;
import componenttest.topology.impl.LibertyServer;

/**
 * This is the test class that will verify that we get the correct behavior when we
 * have mp-config defined as system properties
 * We'll test with a server.xml that will NOT have a mpJwt config, the app will NOT have mp-config specified
 * Therefore, we'll be able to show that the config is coming from the system properties
 * We also test with a conflicting config in server.xml - we'll show that this value overrides the system properties
 *
 **/

@SuppressWarnings("restriction")
@Mode(TestMode.FULL)
@RunWith(FATRunner.class)
public class MPJwtGoodMP12ConfigAsEnvVars_decryptKeyLoc_AlternateDecryptSettings extends GenericEnvVarsAndSystemPropertiesTests {

    public static Class<?> thisClass = MPJwtGoodMP12ConfigAsEnvVars_decryptKeyLoc_AlternateDecryptSettings.class;

    @Server("com.ibm.ws.security.mp.jwt.1.2.fat")
    public static LibertyServer envVarsResourceServer;

    @BeforeClass
    public static void setUp() throws Exception {

        commonMpJwt12Setup(envVarsResourceServer, "rs_server_AltConfigNotInApp_good12ServerXmlConfigNoAudiences.xml", MPJwt12FatConstants.AUTHORIZATION,
                           MPJwt12FatConstants.TOKEN_TYPE_BEARER, MP12ConfigSettings.AudiencesNotSet, MPJwt12FatConstants.SIGALG_RS256,
                           JwtKeyTools.getPrivateKeyFileNameForAlg(MPJwt12FatConstants.SIGALG_RS256),
                           MPConfigLocation.ENV_VAR);

    }

    @Test
    public void MPJwtGoodMP12ConfigAsEnvVars_decryptKeyLoc_AlternateDecryptSettings_keyMgmtKeyAlg256() throws Exception {
        genericDecryptOtherKeyMgmtAlgOrOtherContentEncryptAlg(MPJwt12FatConstants.KEY_MGMT_KEY_ALG_256, MPJwt12FatConstants.DEFAULT_CONTENT_ENCRYPT_ALG);
    }

    @Test
    public void MPJwtGoodMP12ConfigAsEnvVars_decryptKeyLoc_AlternateDecryptSettings_contentEncryptAlg192() throws Exception {
        genericDecryptOtherKeyMgmtAlgOrOtherContentEncryptAlg(MPJwt12FatConstants.DEFAULT_KEY_MGMT_KEY_ALG, MPJwt12FatConstants.CONTENT_ENCRYPT_ALG_192);
    }
}