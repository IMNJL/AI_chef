package com.aichef.repository;

import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserGoogleConnectionRepository extends JpaRepository<UserGoogleConnection, UUID> {
    Optional<UserGoogleConnection> findByUser(User user);

    Optional<UserGoogleConnection> findByIcsToken(String icsToken);
}
