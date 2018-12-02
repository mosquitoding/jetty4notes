//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.hazelcast.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.After;
import org.junit.Before;

/**
 * ClusteredSessionScavengingTest
 */
public class ClusteredSessionScavengingTest
    extends AbstractClusteredSessionScavengingTest
{

    HazelcastSessionDataStoreFactory factory;

    HazelcastTestHelper _testHelper;

    @Before
    public void setUp()
    {
        _testHelper = new HazelcastTestHelper();
    }

    @After
    public void shutdown()
    {
        _testHelper.tearDown();
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _testHelper.createSessionDataStoreFactory(false);
    }

}
