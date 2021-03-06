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

package org.eclipse.jetty.server;

import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SniX509ExtendedKeyManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Customizer that extracts the attribute from an {@link SSLContext}
 * and sets them on the request with {@link ServletRequest#setAttribute(String, Object)}
 * according to Servlet Specification Requirements.</p>
 */
public class SecureRequestCustomizer implements HttpConfiguration.Customizer
{
    private static final Logger LOG = LoggerFactory.getLogger(SecureRequestCustomizer.class);

    /**
     * The name of the SSLSession attribute that will contain any cached information.
     */
    public static final String CACHED_INFO_ATTR = CachedInfo.class.getName();

    private String sslSessionAttribute = "org.eclipse.jetty.servlet.request.ssl_session";

    private boolean _sniRequired;
    private boolean _sniHostCheck;
    private long _stsMaxAge = -1;
    private boolean _stsIncludeSubDomains;
    private HttpField _stsField;

    public SecureRequestCustomizer()
    {
        this(true);
    }

    public SecureRequestCustomizer(@Name("sniHostCheck") boolean sniHostCheck)
    {
        this(sniHostCheck, -1, false);
    }

    /**
     * @param sniHostCheck True if the SNI Host name must match.
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     * @param stsIncludeSubdomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public SecureRequestCustomizer(
        @Name("sniHostCheck") boolean sniHostCheck,
        @Name("stsMaxAgeSeconds") long stsMaxAgeSeconds,
        @Name("stsIncludeSubdomains") boolean stsIncludeSubdomains)
    {
        this(false, sniHostCheck, stsMaxAgeSeconds, stsIncludeSubdomains);
    }

    /**
     * @param sniRequired True if a SNI certificate is required.
     * @param sniHostCheck True if the SNI Host name must match.
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     * @param stsIncludeSubdomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public SecureRequestCustomizer(
        @Name("sniRequired") boolean sniRequired,
        @Name("sniHostCheck") boolean sniHostCheck,
        @Name("stsMaxAgeSeconds") long stsMaxAgeSeconds,
        @Name("stsIncludeSubdomains") boolean stsIncludeSubdomains)
    {
        _sniRequired = sniRequired;
        _sniHostCheck = sniHostCheck;
        _stsMaxAge = stsMaxAgeSeconds;
        _stsIncludeSubDomains = stsIncludeSubdomains;
        formatSTS();
    }

    /**
     * @return True if the SNI Host name must match when there is an SNI certificate.
     */
    public boolean isSniHostCheck()
    {
        return _sniHostCheck;
    }

    /**
     * @param sniHostCheck True if the SNI Host name must match when there is an SNI certificate.
     */
    public void setSniHostCheck(boolean sniHostCheck)
    {
        _sniHostCheck = sniHostCheck;
    }

    /**
     * @return True if SNI is required, else requests will be rejected with 400 response.
     * @see SslContextFactory.Server#isSniRequired()
     */
    public boolean isSniRequired()
    {
        return _sniRequired;
    }

    /**
     * @param sniRequired True if SNI is required, else requests will be rejected with 400 response.
     * @see SslContextFactory.Server#setSniRequired(boolean)
     */
    public void setSniRequired(boolean sniRequired)
    {
        _sniRequired = sniRequired;
    }

    /**
     * @return The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     */
    public long getStsMaxAge()
    {
        return _stsMaxAge;
    }

    /**
     * Set the Strict-Transport-Security max age.
     *
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     */
    public void setStsMaxAge(long stsMaxAgeSeconds)
    {
        _stsMaxAge = stsMaxAgeSeconds;
        formatSTS();
    }

    /**
     * Convenience method to call {@link #setStsMaxAge(long)}
     *
     * @param period The period in units
     * @param units The {@link TimeUnit} of the period
     */
    public void setStsMaxAge(long period, TimeUnit units)
    {
        _stsMaxAge = units.toSeconds(period);
        formatSTS();
    }

    /**
     * @return true if a include subdomain property is sent with any Strict-Transport-Security header
     */
    public boolean isStsIncludeSubDomains()
    {
        return _stsIncludeSubDomains;
    }

    /**
     * @param stsIncludeSubDomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public void setStsIncludeSubDomains(boolean stsIncludeSubDomains)
    {
        _stsIncludeSubDomains = stsIncludeSubDomains;
        formatSTS();
    }

    private void formatSTS()
    {
        if (_stsMaxAge < 0)
            _stsField = null;
        else
            _stsField = new PreEncodedHttpField(HttpHeader.STRICT_TRANSPORT_SECURITY, String.format("max-age=%d%s", _stsMaxAge, _stsIncludeSubDomains ? "; includeSubDomains" : ""));
    }

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        EndPoint endp = request.getHttpChannel().getEndPoint();
        if (endp instanceof DecryptedEndPoint)
        {
            SslConnection.DecryptedEndPoint sslEndp = (DecryptedEndPoint)endp;
            SslConnection sslConnection = sslEndp.getSslConnection();
            SSLEngine sslEngine = sslConnection.getSSLEngine();
            customize(sslEngine, request);

            request.setScheme(HttpScheme.HTTPS.asString());
        }
        else if (endp instanceof ProxyConnectionFactory.ProxyEndPoint)
        {
            ProxyConnectionFactory.ProxyEndPoint proxy = (ProxyConnectionFactory.ProxyEndPoint)endp;
            if (request.getHttpURI().getScheme() == null && proxy.getAttribute(ProxyConnectionFactory.TLS_VERSION) != null)
                request.setScheme(HttpScheme.HTTPS.asString());
        }

        if (HttpScheme.HTTPS.is(request.getScheme()))
            customizeSecure(request);
    }

    /**
     * <p>
     * Customizes the request attributes to be set for SSL requests.
     * </p>
     * <p>
     * The requirements of the Servlet specs are:
     * </p>
     * <ul>
     * <li>an attribute named "javax.servlet.request.ssl_session_id" of type String (since Servlet Spec 3.0).</li>
     * <li>an attribute named "javax.servlet.request.cipher_suite" of type String.</li>
     * <li>an attribute named "javax.servlet.request.key_size" of type Integer.</li>
     * <li>an attribute named "javax.servlet.request.X509Certificate" of type java.security.cert.X509Certificate[]. This
     * is an array of objects of type X509Certificate, the order of this array is defined as being in ascending order of
     * trust. The first certificate in the chain is the one set by the client, the next is the one used to authenticate
     * the first, and so on.</li>
     * </ul>
     *
     * @param sslEngine the sslEngine to be customized.
     * @param request HttpRequest to be customized.
     */
    protected void customize(SSLEngine sslEngine, Request request)
    {
        SSLSession sslSession = sslEngine.getSession();

        if (_sniHostCheck || _sniRequired)
        {
            String name = request.getServerName();
            X509 x509 = (X509)sslSession.getValue(SniX509ExtendedKeyManager.SNI_X509);

            if (LOG.isDebugEnabled())
                LOG.debug("Host {} with SNI {}", name, x509);

            if (x509 == null)
            {
                if (_sniRequired)
                    throw new BadMessageException(400, "SNI required");
            }
            else if (_sniHostCheck && !x509.matches(name))
            {
                throw new BadMessageException(400, "Host does not match SNI");
            }
        }

        try
        {
            String cipherSuite = sslSession.getCipherSuite();
            Integer keySize;
            X509Certificate[] certs;
            String idStr;

            CachedInfo cachedInfo = (CachedInfo)sslSession.getValue(CACHED_INFO_ATTR);
            if (cachedInfo != null)
            {
                keySize = cachedInfo.getKeySize();
                certs = cachedInfo.getCerts();
                idStr = cachedInfo.getIdStr();
            }
            else
            {
                keySize = SslContextFactory.deduceKeyLength(cipherSuite);
                certs = getCertChain(request, sslSession);
                byte[] bytes = sslSession.getId();
                idStr = TypeUtil.toHexString(bytes);
                cachedInfo = new CachedInfo(keySize, certs, idStr);
                sslSession.putValue(CACHED_INFO_ATTR, cachedInfo);
            }

            if (certs != null)
                request.setAttribute("javax.servlet.request.X509Certificate", certs);

            request.setAttribute("javax.servlet.request.cipher_suite", cipherSuite);
            request.setAttribute("javax.servlet.request.key_size", keySize);
            request.setAttribute("javax.servlet.request.ssl_session_id", idStr);
            String sessionAttribute = getSslSessionAttribute();
            if (sessionAttribute != null && !sessionAttribute.isEmpty())
                request.setAttribute(sessionAttribute, sslSession);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to customize request with encryption details", e);
        }
    }

    /**
     * Customizes the request attributes for general secure settings.
     * The default impl calls {@link Request#setSecure(boolean)} with true
     * and sets a response header if the Strict-Transport-Security options
     * are set.
     *
     * @param request the request being customized
     */
    protected void customizeSecure(Request request)
    {
        request.setSecure(true);

        if (_stsField != null)
            request.getResponse().getHttpFields().add(_stsField);
    }

    private X509Certificate[] getCertChain(Request request, SSLSession sslSession)
    {
        // The in-use SslContextFactory should be present in the Connector's SslConnectionFactory
        Connector connector = request.getHttpChannel().getConnector();
        SslConnectionFactory sslConnectionFactory = connector.getConnectionFactory(SslConnectionFactory.class);
        if (sslConnectionFactory != null)
        {
            SslContextFactory sslContextFactory = sslConnectionFactory.getSslContextFactory();
            if (sslConnectionFactory != null)
            {
                return sslContextFactory.getX509CertChain(sslSession);
            }
        }

        // Fallback, either no SslConnectionFactory or no SslContextFactory instance found
        return SslContextFactory.getCertChain(sslSession);
    }

    public void setSslSessionAttribute(String attribute)
    {
        this.sslSessionAttribute = attribute;
    }

    public String getSslSessionAttribute()
    {
        return sslSessionAttribute;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    /**
     * Simple bundle of information that is cached in the SSLSession. Stores the
     * effective keySize and the client certificate chain.
     */
    private static class CachedInfo
    {
        private final X509Certificate[] _certs;
        private final Integer _keySize;
        private final String _idStr;

        CachedInfo(Integer keySize, X509Certificate[] certs, String idStr)
        {
            this._keySize = keySize;
            this._certs = certs;
            this._idStr = idStr;
        }

        X509Certificate[] getCerts()
        {
            return _certs;
        }

        Integer getKeySize()
        {
            return _keySize;
        }

        String getIdStr()
        {
            return _idStr;
        }
    }
}
