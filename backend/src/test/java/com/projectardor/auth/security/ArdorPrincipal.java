package com.projectardor.auth.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.projectardor.auth.domain.UserRole;

/** Exact serialized shape of the old principal; never packaged in the application. */
public record ArdorPrincipal(UUID userId, String email, String passwordHash, boolean enabled, UserRole role)
        implements UserDetails, Serializable {
    private static final long serialVersionUID = 1L;
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    public String getPassword() { return passwordHash; }
    public String getUsername() { return email; }
    public boolean isEnabled() { return enabled; }
}
