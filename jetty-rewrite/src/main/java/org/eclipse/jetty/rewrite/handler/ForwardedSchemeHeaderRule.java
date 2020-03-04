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

package org.eclipse.jetty.rewrite.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;

/**
 * Set the scheme for the request
 */
public class ForwardedSchemeHeaderRule extends HeaderRule
{
    private String _scheme = "https";

    public String getScheme()
    {
        return _scheme;
    }

    /**
     * @param scheme the scheme to set on the request. Defaults to "https"
     */
    public void setScheme(String scheme)
    {
        _scheme = scheme;
    }

    @Override
    protected String apply(String target, String value, HttpServletRequest request, HttpServletResponse response)
    {
        Request baseRequest = Request.getBaseRequest(request);
        baseRequest.setScheme(_scheme);
        return target;
    }
}
