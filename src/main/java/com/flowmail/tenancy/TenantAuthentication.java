package com.flowmail.tenancy;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class TenantAuthentication extends AbstractAuthenticationToken {

    private final TenantPrincipal principal;
    private final Jwt jwt;

    public TenantAuthentication(TenantPrincipal principal, Jwt jwt,
                                Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}