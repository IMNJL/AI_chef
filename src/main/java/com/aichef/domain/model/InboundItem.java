package com.aichef.domain.model;

import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.SourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "inbound_items", indexes = {
        @Index(name = "idx_inbound_items_user_created", columnList = "user_id,created_at")
})
public class InboundItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    @Column(name = "file_url")
    private String fileUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_classification")
    private FilterClassification filterClassification;

    @Column(name = "filter_confidence")
    private Double filterConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategist_payload", columnDefinition = "jsonb")
    private Map<String, Object> strategistPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private InboundStatus processingStatus = InboundStatus.RECEIVED;
}
