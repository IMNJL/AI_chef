package com.aichef.repository;

import com.aichef.domain.model.GoogleOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoogleOAuthStateRepository extends JpaRepository<GoogleOAuthState, String> {
}
