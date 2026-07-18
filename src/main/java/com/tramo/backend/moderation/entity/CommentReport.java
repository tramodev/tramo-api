package com.tramo.backend.moderation.entity;

import com.tramo.backend.comment.entity.Comment;
import com.tramo.backend.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class CommentReport {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "comment_id")
    private Comment comment;

    @ManyToOne
    @JoinColumn(name = "reporter_id")
    private User reporter;

    private String reason;

    private String status = "OPEN";

    private Date createdDate;
}
