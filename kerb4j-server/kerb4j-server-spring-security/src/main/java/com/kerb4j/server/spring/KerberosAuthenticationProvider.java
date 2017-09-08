/*
 * Copyright 2009-2015 the original author or authors.
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

import com.kerb4j.client.SpnegoClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import javax.security.auth.login.LoginException;

/**
 * {@link AuthenticationProvider} for kerberos.
 *
 * @author Mike Wiesner
 * @since 1.0
 */
public class KerberosAuthenticationProvider implements AuthenticationProvider {

    private UserDetailsService userDetailsService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) authentication;
        String validatedUsername;

        try {
            SpnegoClient.loginWithUsernamePassword(auth.getName(), auth.getCredentials().toString());
            validatedUsername = auth.getName(); // TODO: take frmo spnegoClient instead ?
        } catch (LoginException e) {
            throw new BadCredentialsException("Kerberos validation not successful", e);
        }
        UserDetails userDetails = this.userDetailsService.loadUserByUsername(validatedUsername);
		UsernamePasswordAuthenticationToken output = new UsernamePasswordAuthenticationToken(userDetails,
				auth.getCredentials(), userDetails.getAuthorities());
		output.setDetails(authentication.getDetails());
        return output;

    }

    @Override
    public boolean supports(Class<? extends Object> authentication) {
        return (UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication));
    }

    /**
     * Sets the user details service.
     *
     * @param detailsService the new user details service
     */
    public void setUserDetailsService(UserDetailsService detailsService) {
        this.userDetailsService = detailsService;
    }

}
