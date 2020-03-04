//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.derby.jdbc.EmbeddedDataSource;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.plus.security.DataSourceLoginService;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DataSourceLoginServiceTest
 */
public class DataSourceLoginServiceTest
{

    public static final String _content = "This is some protected content";
    private static File _docRoot;
    private static HttpClient _client;
    private static String __realm = "DSRealm";
    private static URI _baseUri;
    private static DatabaseLoginServiceTestServer _testServer;

    @BeforeAll
    public static void setUp() throws Exception
    {

        _docRoot = MavenTestingUtils.getTargetTestingDir("loginservice-test");
        FS.ensureDirExists(_docRoot);

        File content = new File(_docRoot, "input.txt");
        FileOutputStream out = new FileOutputStream(content);
        out.write(_content.getBytes("utf-8"));
        out.close();

        //clear previous runs
        File scriptFile = MavenTestingUtils.getTestResourceFile("droptables.sql");
        int result = DatabaseLoginServiceTestServer.runscript(scriptFile);
        //ignore result as derby spits errors for dropping tables that dont exist

        //create afresh
        scriptFile = MavenTestingUtils.getTestResourceFile("createdb.sql");
        result = DatabaseLoginServiceTestServer.runscript(scriptFile);
        assertThat("runScript result", result, is(0));

        _testServer = new DatabaseLoginServiceTestServer();
        _testServer.setResourceBase(_docRoot.getAbsolutePath());
        _testServer.setLoginService(configureLoginService());
        _testServer.start();
        _baseUri = _testServer.getBaseUri();
    }

    @AfterAll
    public static void tearDown()
        throws Exception
    {
        if (_testServer != null)
        {
            _testServer.stop();
            _testServer = null;
        }
    }

    public static DataSourceLoginService configureLoginService() throws Exception
    {
        DataSourceLoginService loginService = new DataSourceLoginService();
        loginService.setUserTableName("users");
        loginService.setUserTableKey("id");
        loginService.setUserTableUserField("username");
        loginService.setUserTablePasswordField("pwd");
        loginService.setRoleTableName("roles");
        loginService.setRoleTableKey("id");
        loginService.setRoleTableRoleField("role");
        loginService.setUserRoleTableName("user_roles");
        loginService.setUserRoleTableRoleKey("role_id");
        loginService.setUserRoleTableUserKey("user_id");
        loginService.setJndiName("dstest");
        loginService.setName(__realm);
        if (_testServer != null)
            loginService.setServer(_testServer.getServer());

        //create a datasource
        EmbeddedDataSource ds = new EmbeddedDataSource();
        File db = new File(DatabaseLoginServiceTestServer.getDbRoot(), "loginservice");
        ds.setDatabaseName(db.getAbsolutePath());
        org.eclipse.jetty.plus.jndi.Resource binding = new org.eclipse.jetty.plus.jndi.Resource(null, "dstest",
            ds);
        assertThat("Created binding for dstest", binding, notNullValue());
        return loginService;
    }

    @Test
    public void testGetAndPasswordUpdate() throws Exception
    {
        try
        {
            startClient("jetty", "jetty");

            ContentResponse response = _client.GET(_baseUri.resolve("input.txt"));
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals(_content, response.getContentAsString());

            stopClient();

            String newpwd = String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));

            changePassword("jetty", newpwd);

            startClient("jetty", newpwd);

            response = _client.GET(_baseUri.resolve("input.txt"));
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals(_content, response.getContentAsString());
        }
        finally
        {
            stopClient();
        }
    }

    protected void changePassword(String user, String newpwd) throws Exception
    {
        Loader.loadClass("org.apache.derby.jdbc.EmbeddedDriver").getDeclaredConstructor().newInstance();
        try (Connection connection = DriverManager.getConnection(DatabaseLoginServiceTestServer.__dbURL, "", "");
             Statement stmt = connection.createStatement())
        {
            connection.setAutoCommit(true);
            stmt.executeUpdate("update users set pwd='" + newpwd + "' where username='" + user + "'");
        }
    }

    protected void startClient(String user, String pwd) throws Exception
    {
        _client = new HttpClient();
        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");
        _client.setExecutor(executor);
        AuthenticationStore authStore = _client.getAuthenticationStore();
        authStore.addAuthentication(new BasicAuthentication(_baseUri, __realm, user, pwd));
        _client.start();
    }

    protected void stopClient() throws Exception
    {
        if (_client != null)
        {
            _client.stop();
            _client = null;
        }
    }
}
