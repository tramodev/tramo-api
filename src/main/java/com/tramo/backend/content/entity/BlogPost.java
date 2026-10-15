package com.tramo.backend.content.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class BlogPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 120)
    private String slug;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 1000)
    private String summary;
    @Column(nullable = false, length = 80)
    private String category;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    private boolean published;
    @Column(nullable = false)
    private LocalDate publishedDate;
}
