package com.aichef.domain.model;

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
@Table(name = "user_rules", indexes = {
        @Index(name = "idx_user_rules_user_id", columnList = "user_id")
})
public class UserRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_value", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> ruleValue = new HashMap<>();

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean active = true;
}
