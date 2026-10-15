package com.tramo.backend.content.repository;

import com.tramo.backend.content.entity.BlogPost;
import com.tramo.backend.content.dto.BlogPostSummaryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {
    List<BlogPost> findAllByOrderByPublishedDateDescIdDesc();
    Optional<BlogPost> findBySlugAndPublishedTrue(String slug);

    @Query("select new com.tramo.backend.content.dto.BlogPostSummaryDTO(p.slug, p.title, p.summary, p.category, p.publishedDate) from BlogPost p where p.published = true order by p.publishedDate desc, p.id desc")
    List<BlogPostSummaryDTO> findPublishedSummaries();
}
