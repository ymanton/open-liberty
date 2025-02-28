/*******************************************************************************
 * Copyright (c) 2019, 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.microprofile.rest.client.fat;

import java.util.HashSet;
import java.util.Set;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;
import componenttest.rules.repeater.FeatureReplacementAction;
import componenttest.rules.repeater.JakartaEE9Action;

@RunWith(Suite.class)
@SuiteClasses({
                RetryTest.class,
                TimeoutTest.class
})
public class FATSuite {
    private static final String[] ALL_VERSIONS = {"1.0", "1.1", "1.2", "1.3", "1.4", "2.0", "3.0"};

    private static FeatureReplacementAction MP_REST_CLIENT_WITH_CONFIG(FeatureReplacementAction action, String version, String serverName) {
        action = use(action, "mpRestClient", version).forServers(serverName);
        switch(version) {
            case "1.0":
            case "1.1": return use(action, "mpConfig", "1.1", "1.0", "1.2", "1.3", "1.4", "2.0", "3.0");
            case "1.2":
            case "1.3": return use(action, "mpConfig", "1.3", "1.0", "1.1", "1.2", "1.4", "2.0", "3.0");
            case "1.4": return use(action, "mpConfig", "1.4", "1.0", "1.1", "1.2", "1.3", "2.0", "3.0");
            case "2.0": return use(action, "mpConfig", "2.0", "1.0", "1.1", "1.2", "1.3", "1.4", "3.0");
            case "3.0":
            default:    return use(action, "mpConfig", "3.0", "1.0", "1.1", "1.2", "1.3", "1.4", "2.0");
        }
    }

    static FeatureReplacementAction MP_REST_CLIENT_WITH_CONFIG_AND_FT(String version, String serverName) {
        FeatureReplacementAction action;
        if ("2.0".equals(version)) {
            action = use(MP_REST_CLIENT_WITH_CONFIG(new FeatureReplacementAction(), version, serverName), "mpFaultTolerance", "3.0");
            action = action.withID("mpRestClient-" + version);
        } else if ("3.0".equals(version)) {
            action = use(MP_REST_CLIENT_WITH_CONFIG(new JakartaEE9Action(), version, serverName), "mpFaultTolerance", "4.0");
            // don't reset ID here - otherwise the JakartaEE9Action won't transform the app code.
        } else {
            action = MP_REST_CLIENT_WITH_CONFIG(new FeatureReplacementAction(), version, serverName);
            action = action.withID("mpRestClient-" + version);
        }
        return action;
    }
    private static FeatureReplacementAction use(FeatureReplacementAction action, String featureName, String version) {
        return use(action, featureName, version, ALL_VERSIONS);
    }

    private static FeatureReplacementAction use(FeatureReplacementAction action, String featureName, String version, String... versionsToRemove) {
        action = action.addFeature(featureName + "-" + version);
        Set<String> featuresToRemove = new HashSet<>();
        for (String remove : versionsToRemove) {
            if (!version.equals(remove)) {
                featuresToRemove.add(featureName + "-" + remove);
            }
        }
        action = action.removeFeatures(featuresToRemove);
        return action;
    }
}
