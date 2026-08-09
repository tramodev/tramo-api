package com.tramo.backend.notification;

import java.util.List;
import java.util.Set;

public final class NotificationTypes {
    public static final String UPVOTE = "UPVOTE";
    public static final String COMMENT = "COMMENT";
    public static final String FORK = "FORK";
    public static final String FOLLOW = "FOLLOW";
    public static final String PUBLISH = "PUBLISH";
    public static final String SHARE = "SHARE";
    public static final String BADGE = "BADGE";
    public static final String FEATURED = "FEATURED";

    public static final List<String> ALL = List.of(UPVOTE, COMMENT, FORK, FOLLOW, PUBLISH, SHARE, BADGE, FEATURED);

    private static final Set<String> KNOWN = Set.copyOf(ALL);

    public static boolean isKnown(String type) {
        return type != null && KNOWN.contains(type);
    }

    private NotificationTypes() {
    }
}
