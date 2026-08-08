package com.tramo.backend.notification.repository;

import com.tramo.backend.notification.entity.Notification;
import com.tramo.backend.user.entity.User;

import java.util.Date;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query(value = "SELECT n FROM Notification n LEFT JOIN FETCH n.project LEFT JOIN FETCH n.latestActor WHERE n.recipient.id = :recipientId ORDER BY n.updatedDate DESC",
            countQuery = "SELECT COUNT(n) FROM Notification n WHERE n.recipient.id = :recipientId")
    Page<Notification> findByRecipientIdOrderByUpdatedDateDesc(@Param("recipientId") Long recipientId, Pageable pageable);

    long countByRecipientIdAndReadFalse(Long recipientId);


    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.count = n.count + 1, n.latestActor = :actor, n.updatedDate = :now "
            + "WHERE n.recipient.id = :recipientId AND n.type = :type AND n.project.id = :projectId AND n.read = false")
    int incrementForProject(@Param("recipientId") Long recipientId, @Param("type") String type,
                            @Param("projectId") Long projectId, @Param("actor") User actor, @Param("now") Date now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.count = n.count + 1, n.latestActor = :actor, n.updatedDate = :now "
            + "WHERE n.recipient.id = :recipientId AND n.type = :type AND n.project IS NULL AND n.read = false")
    int incrementWithoutProject(@Param("recipientId") Long recipientId, @Param("type") String type,
                                @Param("actor") User actor, @Param("now") Date now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    void markAllReadByRecipientId(@Param("recipientId") Long recipientId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.id = :id and n.recipient.id = :recipientId")
    int deleteByIdAndRecipientId(@Param("id") Long id, @Param("recipientId") Long recipientId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.read = true and n.updatedDate < :cutoff")
    int deleteReadOlderThan(@Param("cutoff") Date cutoff);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.recipient.id = :recipientId")
    void deleteByRecipientId(@Param("recipientId") Long recipientId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.latestActor = null WHERE n.latestActor.id = :actorId")
    void clearLatestActorReferences(@Param("actorId") Long actorId);
}
