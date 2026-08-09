package com.tramo.backend.user.entity;

import com.tramo.backend.project.entity.Project;
import com.tramo.backend.security.crypto.EncryptedStringConverter;
import com.tramo.backend.subscription.entity.Subscription;
import com.tramo.backend.user.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name="users", uniqueConstraints = {@UniqueConstraint(columnNames = {"username"})})
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    @Column(columnDefinition = "text")
    private String bio;
    private LocalDate birthDate;
    @Column(columnDefinition = "text")
    private String location;
    @Column(columnDefinition = "text")
    private String website;
    private Boolean showAge;
    @Column(columnDefinition = "text")
    private String imageUrl;
    @Column(columnDefinition = "text")
    private String bannerUrl;
    private Boolean visibility;
    private Date createdAt;
    private Date updatedAt;
    @OneToMany(mappedBy="owner")
    private List<Project> projects;
    private Role role;
    private String selectedBadge;
    @Column(columnDefinition = "boolean default true")
    private boolean emailVerified;

    @Column(columnDefinition = "boolean default false")
    private boolean banned;

    private String emailDigestFrequency;
    private Boolean showUpvotes;
    private Boolean allowForks;
    private String commentsPolicy;
    private Boolean editorTourSeen;
    private Boolean notificationsEnabled;
    private String mutedNotificationTypes;

    @Column(unique = true)
    private String patreonUserId;
    @Column(columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String patreonAccessToken;
    @Column(columnDefinition = "text")
    @Convert(converter = EncryptedStringConverter.class)
    private String patreonRefreshToken;

    // Not persisted - populated from the JWT's requiresBirthDate claim when the
    // principal is reconstructed from a token (JwtService.buildPrincipalFromClaims),
    // since that path never hits the DB and birthDate itself is never a claim.
    @Transient
    private Boolean requiresBirthDate;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority((role.name())));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !banned;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return emailVerified;
    }
}