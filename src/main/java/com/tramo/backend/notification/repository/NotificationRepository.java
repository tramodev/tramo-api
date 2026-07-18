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

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    void markAllReadByRecipientId(@Param("recipientId") Long recipientId);

    long deleteByIdAndRecipientId(Long id, Long recipientId);

    void deleteByProjectId(Long projectId);

    void deleteByRecipientId(Long recipientId);

    @Modifying
    @Query("UPDATE Notification n SET n.latestActor = null WHERE n.latestActor.id = :actorId")
    void clearLatestActorReferences(@Param("actorId") Long actorId);
}
