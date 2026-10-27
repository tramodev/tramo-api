// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    String VISIBLE = "n.recipient.id = :recipientId AND (n.type = 'MODERATION' OR NOT EXISTS (SELECT b.id FROM BlockedUser b "
            + "WHERE (b.blocker.id = :recipientId AND b.blocked = n.latestActor) "
            + "OR (b.blocked.id = :recipientId AND b.blocker = n.latestActor)))";

    @Query(value = "SELECT n FROM Notification n LEFT JOIN FETCH n.project LEFT JOIN FETCH n.latestActor WHERE " + VISIBLE + " ORDER BY n.updatedDate DESC, n.id DESC",
            countQuery = "SELECT COUNT(n) FROM Notification n WHERE " + VISIBLE)
    Page<Notification> findByRecipientIdOrderByUpdatedDateDesc(@Param("recipientId") Long recipientId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE " + VISIBLE + " AND n.read = false")
    long countByRecipientIdAndReadFalse(@Param("recipientId") Long recipientId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notification SET count = (SELECT COUNT(*) FROM project_vote v "
            + "WHERE v.project_id = :projectId AND v.user_id <> :recipientId AND v.created_date >= notification.created_date), "
            + "actor_id = (SELECT v.user_id FROM project_vote v WHERE v.project_id = :projectId "
            + "AND v.user_id <> :recipientId AND v.created_date >= notification.created_date ORDER BY v.created_date DESC, v.id DESC LIMIT 1) "
            + "WHERE recipient_id = :recipientId AND project_id = :projectId AND type = 'UPVOTE' AND read = false", nativeQuery = true)
    void syncUpvotes(@Param("recipientId") Long recipientId, @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.recipient.id = :recipientId AND n.project.id = :projectId "
            + "AND n.type = 'UPVOTE' AND n.read = false AND n.count = 0")
    void deleteEmptyUpvotes(@Param("recipientId") Long recipientId, @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notification SET count = (SELECT COUNT(*) FROM follow f "
            + "WHERE f.followed_id = :recipientId AND f.created_date >= notification.created_date), "
            + "actor_id = (SELECT f.follower_id FROM follow f WHERE f.followed_id = :recipientId "
            + "AND f.created_date >= notification.created_date ORDER BY f.created_date DESC, f.id DESC LIMIT 1) "
            + "WHERE recipient_id = :recipientId AND type = 'FOLLOW' AND read = false", nativeQuery = true)
    void syncFollows(@Param("recipientId") Long recipientId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notification SET count = (SELECT COUNT(DISTINCT c.author_id) FROM comment c "
            + "WHERE c.project_id = :projectId AND c.author_id <> :recipientId AND c.deleted = false "
            + "AND c.created_date >= notification.created_date), "
            + "actor_id = (SELECT c.author_id FROM comment c WHERE c.project_id = :projectId "
            + "AND c.author_id <> :recipientId AND c.deleted = false AND c.created_date >= notification.created_date "
            + "ORDER BY c.created_date DESC, c.id DESC LIMIT 1) "
            + "WHERE recipient_id = :recipientId AND project_id = :projectId AND type = 'COMMENT' AND read = false", nativeQuery = true)
    void syncComments(@Param("recipientId") Long recipientId, @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notification SET count = (SELECT COUNT(DISTINCT p.user_id) FROM project p "
            + "WHERE p.forked_from_id = :projectId AND p.user_id <> :recipientId "
            + "AND p.creation_date >= notification.created_date), "
            + "actor_id = (SELECT p.user_id FROM project p WHERE p.forked_from_id = :projectId "
            + "AND p.user_id <> :recipientId AND p.creation_date >= notification.created_date "
            + "ORDER BY p.creation_date DESC, p.id DESC LIMIT 1) "
            + "WHERE recipient_id = :recipientId AND project_id = :projectId AND type = 'FORK' AND read = false", nativeQuery = true)
    void syncForks(@Param("recipientId") Long recipientId, @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.recipient.id = :recipientId AND n.type = :type "
            + "AND (:projectId IS NULL OR n.project.id = :projectId) AND n.read = false AND n.count = 0")
    void deleteEmptyActivity(@Param("recipientId") Long recipientId, @Param("type") String type,
                             @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.recipient.id = :recipientId")
    int markReadByIdAndRecipientId(@Param("id") Long id, @Param("recipientId") Long recipientId);


    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.count = n.count + 1, n.latestActor = :actor, n.updatedDate = :now "
            + "WHERE n.recipient.id = :recipientId AND n.type = :type AND n.project.id = :projectId AND n.read = false")
    int incrementForProject(@Param("recipientId") Long recipientId, @Param("type") String type,
                            @Param("projectId") Long projectId, @Param("actor") User actor, @Param("now") Date now);

    @Query("SELECT n.recipient.id FROM Notification n WHERE n.recipient.id IN :recipientIds "
            + "AND n.type = :type AND n.project.id = :projectId AND n.read = false")
    List<Long> findUnreadRecipientIds(@Param("recipientIds") Collection<Long> recipientIds,
                                      @Param("type") String type, @Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.count = n.count + 1, n.latestActor = :actor, n.updatedDate = :now "
            + "WHERE n.recipient.id IN :recipientIds AND n.type = :type AND n.project.id = :projectId AND n.read = false")
    int incrementForProjectIn(@Param("recipientIds") Collection<Long> recipientIds, @Param("type") String type,
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
