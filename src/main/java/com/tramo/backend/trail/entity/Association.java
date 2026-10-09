package com.tramo.backend.trail.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"source_item_id", "target_id"}),
        indexes = @Index(name = "idx_association_target", columnList = "target_id"))
public class Association {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_item_id", nullable = false)
    private Item sourceItem;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private Item targetItem;
    @Column(length = 4002)
    private String text;
    @Column(nullable = false)
    private Long projectId;
    private Date createdDate;

    public Long getTargetId() {
        return targetItem.getId();
    }
}
