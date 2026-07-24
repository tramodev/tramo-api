package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    // Every item that belongs to the project (whether in trails or not).
    // Join-fetch the EAGER @OneToOne content so a page of items is one query, not N+1.
    @Query("SELECT i FROM Item i LEFT JOIN FETCH i.content WHERE i.project.id = :projectId")
    List<Item> findByProjectId(@Param("projectId") Long projectId);

    // id + title only: title lookups for association targets must not drag in each
    // item's EAGER content (that would be an N+1 hidden behind findAllById).
    @Query("SELECT i.id, i.title FROM Item i WHERE i.id IN :ids")
    List<Object[]> findIdTitleByIdIn(@Param("ids") Collection<Long> ids);
}
