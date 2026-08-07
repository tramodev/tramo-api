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
@Table(indexes = {
        @Index(name = "idx_comment_report_comment_status", columnList = "comment_id, status"),
        @Index(name = "idx_comment_report_reporter", columnList = "reporter_id"),
        @Index(name = "idx_comment_report_status_created", columnList = "status, created_date DESC"),
})
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

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReportStatus status = ReportStatus.OPEN;

    private Date createdDate;
}
