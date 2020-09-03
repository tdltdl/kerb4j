/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kerb4j.server.spring;

import com.kerb4j.common.util.Constants;
import com.kerb4j.common.util.base64.Base64Codec;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Parses the SPNEGO authentication Header, which was generated by the browser
 * and creates a {@link SpnegoRequestToken} out if it. It will then
 * call the {@link AuthenticationManager}.
 *
 * <p>A typical Spring Security configuration might look like this:</p>
 *
 * <pre>
 * &lt;beans xmlns=&quot;http://www.springframework.org/schema/beans&quot;
 * xmlns:xsi=&quot;http://www.w3.org/2001/XMLSchema-instance&quot; xmlns:sec=&quot;http://www.springframework.org/schema/security&quot;
 * xsi:schemaLocation=&quot;http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.0.xsd
 * 	http://www.springframework.org/schema/security http://www.springframework.org/schema/security/spring-security-3.0.xsd&quot;&gt;
 *
 * &lt;sec:http entry-point-ref=&quot;spnegoEntryPoint&quot;&gt;
 * 	&lt;sec:intercept-url pattern=&quot;/secure/**&quot; access=&quot;IS_AUTHENTICATED_FULLY&quot; /&gt;
 * 	&lt;sec:custom-filter ref=&quot;spnegoAuthenticationProcessingFilter&quot; position=&quot;BASIC_AUTH_FILTER&quot; /&gt;
 * &lt;/sec:http&gt;
 *
 * &lt;bean id=&quot;spnegoEntryPoint&quot; class=&quot;com.kerb4j.server.spring.SpnegoEntryPoint&quot; /&gt;
 *
 * &lt;bean id=&quot;spnegoAuthenticationProcessingFilter&quot;
 * 	class=&quot;com.kerb4j.server.spring.SpnegoAuthenticationProcessingFilter&quot;&gt;
 * 	&lt;property name=&quot;authenticationManager&quot; ref=&quot;authenticationManager&quot; /&gt;
 * &lt;/bean&gt;
 *
 * &lt;sec:authentication-manager alias=&quot;authenticationManager&quot;&gt;
 * 	&lt;sec:authentication-provider ref=&quot;kerberosServiceAuthenticationProvider&quot; /&gt;
 * &lt;/sec:authentication-manager&gt;
 *
 * &lt;bean id=&quot;kerberosServiceAuthenticationProvider&quot;
 * 	class=&quot;org.springframework.security.kerberos.authenitcation.SpnegoAuthenticationProvider&quot;&gt;
 * 	&lt;property name=&quot;ticketValidator&quot;&gt;
 * 		&lt;bean class=&quot;com.kerb4j.server.spring.jaas.sun.SunJaasKerberosTicketValidator&quot;&gt;
 * 			&lt;property name=&quot;servicePrincipal&quot; value=&quot;HTTP/web.springsource.com&quot; /&gt;
 * 			&lt;property name=&quot;keyTabLocation&quot; value=&quot;classpath:http-java.keytab&quot; /&gt;
 * 		&lt;/bean&gt;
 * 	&lt;/property&gt;
 * 	&lt;property name=&quot;userDetailsService&quot; ref=&quot;inMemoryUserDetailsService&quot; /&gt;
 * &lt;/bean&gt;
 *
 * &lt;bean id=&quot;inMemoryUserDetailsService&quot;
 * 	class=&quot;org.springframework.security.core.userdetails.memory.InMemoryDaoImpl&quot;&gt;
 * 	&lt;property name=&quot;userProperties&quot;&gt;
 * 		&lt;value&gt;
 * 			mike@SECPOD.DE=notUsed,ROLE_ADMIN
 * 		&lt;/value&gt;
 * 	&lt;/property&gt;
 * &lt;/bean&gt;
 * &lt;/beans&gt;
 * </pre>
 *
 * <p>If you get a "GSSException: Channel binding mismatch (Mechanism
 * level:ChannelBinding not provided!) have a look at this <a
 * href="http://bugs.sun.com/view_bug.do?bug_id=6851973">bug</a>.</p>
 * <p>A workaround unti this is fixed in the JVM is to change</p>
 * HKEY_LOCAL_MACHINE\System
 * \CurrentControlSet\Control\LSA\SuppressExtendedProtection to 0x02
 *
 * @author Mike Wiesner
 * @author Jeremy Stone
 * @see SpnegoAuthenticationProvider
 * @see SpnegoEntryPoint
 * @since 1.0
 */
public class SpnegoAuthenticationProcessingFilter extends OncePerRequestFilter {

    private AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource = new WebAuthenticationDetailsSource();
    private AuthenticationManager authenticationManager;
    private AuthenticationSuccessHandler authenticationSuccessHandler;
    private AuthenticationFailureHandler authenticationFailureHandler;
    private SessionAuthenticationStrategy sessionAuthenticationStrategy = new NullAuthenticatedSessionStrategy();
    private boolean skipIfAlreadyAuthenticated = true;

    private boolean supportBasicAuthentication;

    public SpnegoAuthenticationProcessingFilter() {
        this(true);
    }

    public SpnegoAuthenticationProcessingFilter(boolean supportBasicAuthentication) {
        this.supportBasicAuthentication = supportBasicAuthentication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        if (skipIfAlreadyAuthenticated) {
            Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();

            if (existingAuth != null && existingAuth.isAuthenticated() && !(existingAuth instanceof AnonymousAuthenticationToken)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String header = request.getHeader(Constants.AUTHZ_HEADER);

        // TODO: spring-security-kerberos used to support "Kerberos" scheme. Is it a valid use case?
        if (header != null) {

            AbstractAuthenticationToken authenticationRequest;

            if (header.startsWith(Constants.NEGOTIATE_HEADER)) {

                if (logger.isDebugEnabled()) {
                    logger.debug("Received Negotiate Header for request " + request.getRequestURL() + ": " + header);
                }
                byte[] base64Token = header.substring(header.indexOf(" ") + 1).getBytes("UTF-8");
                byte[] kerberosTicket = Base64.decode(base64Token);
                authenticationRequest = new SpnegoRequestToken(kerberosTicket);

            } else if (supportBasicAuthentication && header.startsWith(Constants.BASIC_HEADER)) {

                String[] strings = extractAndDecodeHeader(header);
                authenticationRequest = new UsernamePasswordAuthenticationToken(strings[0], strings[1]);

            } else {

                filterChain.doFilter(request, response);
                return;

            }

            authenticationRequest.setDetails(authenticationDetailsSource.buildDetails(request));
            Authentication authentication;
            try {
                authentication = authenticationManager.authenticate(authenticationRequest);
            } catch (AuthenticationException e) {
                // That shouldn't happen, as it is most likely a wrong
                // configuration on the server side
                logger.warn("Negotiate Header was invalid: " + header, e);
                SecurityContextHolder.clearContext();
                if (authenticationFailureHandler != null) {
                    authenticationFailureHandler.onAuthenticationFailure(request, response, e);
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.flushBuffer();
                }
                return;
            }
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // this.rememberMeServices.loginSuccess(request, response, authResult); ??
            if (authenticationSuccessHandler != null) {
                authenticationSuccessHandler.onAuthenticationSuccess(request, response, authentication);
            }

            filterChain.doFilter(request, response);

        } else {
            filterChain.doFilter(request, response);
        }

    }


    private String[] extractAndDecodeHeader(String header)
            throws IOException {

        String base64Token = header.substring(6);
        byte[] decoded;
        try {
            decoded = Base64Codec.decode(base64Token);
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException(
                    "Failed to decode basic authentication token");
        }

        String token = new String(decoded, "UTF-8");

        int delim = token.indexOf(":");

        if (delim == -1) {
            throw new BadCredentialsException("Invalid basic authentication token");
        }
        return new String[]{token.substring(0, delim), token.substring(delim + 1)};
    }

    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        Assert.notNull(this.authenticationManager, "authenticationManager must be specified");
    }

    /**
     * The authentication manager for validating the ticket.
     *
     * @param authenticationManager the authentication manager
     */
    public void setAuthenticationManager(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * <p>This handler is called after a successful authentication. One can add
     * additional authentication behavior by setting this.</p>
     * <p>Default is null, which means nothing additional happens</p>
     *
     * @param authenticationSuccessHandler the authentication success handler
     */
    public void setAuthenticationSuccessHandler(AuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

    /**
     * <p>This handler is called after a failure authentication. In most cases you
     * only get Kerberos/SPNEGO failures with a wrong server or network
     * configurations and not during runtime. If the client encounters an error,
     * he will just stop the communication with server and therefore this
     * handler will not be called in this case.</p>
     * <p>Default is null, which means that the Filter returns the HTTP 500 code</p>
     *
     * @param authenticationFailureHandler the authentication failure handler
     */
    public void setAuthenticationFailureHandler(AuthenticationFailureHandler authenticationFailureHandler) {
        this.authenticationFailureHandler = authenticationFailureHandler;
    }


    /**
     * Should Kerberos authentication be skipped if a user is already authenticated
     * for this request (e.g. in the HTTP session).
     *
     * @param skipIfAlreadyAuthenticated default is true
     */
    public void setSkipIfAlreadyAuthenticated(boolean skipIfAlreadyAuthenticated) {
        this.skipIfAlreadyAuthenticated = skipIfAlreadyAuthenticated;
    }

    /**
     * The session handling strategy which will be invoked immediately after an
     * authentication request is successfully processed by the
     * {@link AuthenticationManager}. Used, for example, to handle changing of
     * the session identifier to prevent session fixation attacks.
     *
     * @param sessionStrategy the implementation to use. If not set a null
     *                        implementation is used.
     */
    public void setSessionAuthenticationStrategy(SessionAuthenticationStrategy sessionStrategy) {
        this.sessionAuthenticationStrategy = sessionStrategy;
    }


    /**
     * Sets the authentication details source.
     *
     * @param authenticationDetailsSource the authentication details source
     */
    public void setAuthenticationDetailsSource(
            AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource) {
        Assert.notNull(authenticationDetailsSource, "AuthenticationDetailsSource required");
        this.authenticationDetailsSource = authenticationDetailsSource;
    }

    public void setSupportBasicAuthentication(boolean supportBasicAuthentication) {
        this.supportBasicAuthentication = supportBasicAuthentication;
    }

}
