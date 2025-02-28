/*******************************************************************************
 * Copyright (c) 2021, 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.microprofile.graphql.tck;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import componenttest.annotation.AllowedFFDC;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.MvnUtils;

/**
 * This is a test class that runs a whole Maven TCK as one test FAT test.
 * There is a detailed output on specific
 */
@RunWith(FATRunner.class)
public class GraphQLTckPackageTest {

    @Server("FATServer")
    public static LibertyServer server;

    @AfterClass
    public static void tearDown() throws Exception {
        if (server != null) {
            server.postStopServerArchive(); // must explicitly collect since arquillian is starting/stopping the server
        }
    }

    @Test
    @AllowedFFDC // The tested deployment exceptions cause FFDC so we have to allow for this.
    public void testGraphQLTck() throws Exception {
        MvnUtils.runTCKMvnCmd(server, "io.openliberty.microprofile.graphql.2.0.internal_fat_tck", this.getClass() + ":testGraphQLTck");
        Map<String, String> resultInfo = MvnUtils.getResultInfo(server);
        resultInfo.put("results_type", "MicroProfile");
        resultInfo.put("feature_name", "GraphQL");
        resultInfo.put("feature_version", "2.0");
        MvnUtils.preparePublicationFile(resultInfo);
    }
}