package com.aichef.repository;

import com.aichef.domain.model.InboundItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InboundItemRepository extends JpaRepository<InboundItem, UUID> {
}
