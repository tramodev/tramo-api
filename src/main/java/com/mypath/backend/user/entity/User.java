package com.mypath.backend.user.entity;

import com.mypath.backend.project.entity.Project;
import com.mypath.backend.subscription.entity.Subscription;
import com.mypath.backend.user.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

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
    private String bio;
    private String imageUrl;
    private Boolean visibility;
    private Date createdAt;
    private Date updatedAt;
    @OneToMany(mappedBy="owner")
    private List<Project> projects;
    private Role role;
    // Backfilled true for pre-existing rows via the column default; new registrations
    // explicitly set this false until the verification link is clicked.
    @Column(columnDefinition = "boolean default true")
    private boolean emailVerified;
//    @OneToOne(cascade = CascadeType.ALL)
//    private Subscription subscription;

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
        return true;
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