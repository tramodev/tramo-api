package com.tramo.backend.subscription.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Entity
@Getter @Setter
@NoArgsConstructor
public class Subscription {
    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private Long id;
    private Date startDate;
    private Date endDate;
    private String status;
    @OneToOne
    private Plan plan;
    @OneToMany
    private List<Payment> payment;

}
