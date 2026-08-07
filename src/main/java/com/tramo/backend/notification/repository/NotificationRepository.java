package com.tramo.backend.notification.repository;

import com.tramo.backend.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query("SELECT n FROM Notification n LEFT JOIN FETCH n.project LEFT JOIN FETCH n.latestActor WHERE n.recipient.id = :recipientId ORDER BY n.updatedDate DESC")
    List<Notification> findByRecipientIdOrderByUpdatedDateDesc(@Param("recipientId") Long recipientId);

    long countByRecipientIdAndReadFalse(Long recipientId);

    Optional<Notification> findByRecipientIdAndTypeAndProjectIdAndReadFalse(Long recipientId, String type, Long projectId);

    Optional<Notification> findByRecipientIdAndTypeAndProjectIsNullAndReadFalse(Long recipientId, String type);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    void markAllReadByRecipientId(@Param("recipientId") Long recipientId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.id = :id and n.recipient.id = :recipientId")
    int deleteByIdAndRecipientId(@Param("id") Long id, @Param("recipientId") Long recipientId);

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
