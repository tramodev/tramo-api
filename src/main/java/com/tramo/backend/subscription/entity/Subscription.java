package com.tramo.backend.subscription.entity;

import com.tramo.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Entity
@Getter @Setter
@NoArgsConstructor
@Table(indexes = @Index(name = "idx_subscription_user_status", columnList = "user_id, status"))
public class Subscription {
    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private Long id;
    private Date startDate;
    private Date endDate;
    private String status;  // ACTIVE | CANCELED
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
    @OneToOne
    private Plan plan;
    @OneToMany
    private List<Payment> payment;

}
