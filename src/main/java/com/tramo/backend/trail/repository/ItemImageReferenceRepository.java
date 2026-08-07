package com.tramo.backend.trail.repository;

import com.tramo.backend.trail.entity.ItemImageReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ItemImageReferenceRepository extends JpaRepository<ItemImageReference, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from ItemImageReference r where r.item.id = :itemId")
    void deleteByItemId(@Param("itemId") Long itemId);

    @Query("SELECT r FROM ItemImageReference r JOIN FETCH r.item i WHERE i.project.id = :projectId " +
            "ORDER BY i.id ASC, r.id ASC")
    List<ItemImageReference> findByProjectIdOrderByItemIdAsc(@Param("projectId") Long projectId);

    @Query("SELECT r FROM ItemImageReference r JOIN FETCH r.item i WHERE i.project.id IN :projectIds " +
            "ORDER BY i.project.id ASC, i.id ASC, r.id ASC")
    List<ItemImageReference> findByProjectIdInOrderByItemIdAsc(@Param("projectIds") Collection<Long> projectIds);

    @Query("SELECT COUNT(r) > 0 FROM ItemImageReference r " +
            "WHERE r.url = :url AND r.item.id <> :excludeItemId AND r.item.project.owner.id = :ownerId")
    boolean existsOtherItemReferencingUrl(@Param("ownerId") Long ownerId, @Param("url") String url,
                                           @Param("excludeItemId") Long excludeItemId);
}
