// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.common;

import com.tramo.backend.exception.ResourceNotFoundException;
import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProjectIdCodec {
    private final Hashids hashids;

    public ProjectIdCodec(@Value("${app.project-id.salt}") String salt) {
        this.hashids = new Hashids(salt, 6);
    }

    public String encode(Long id) {
        return hashids.encode(id);
    }

    public Long decode(String hash) {
        long[] decoded = hashids.decode(hash);
        if (decoded.length == 0) {
            throw new ResourceNotFoundException("Project not found");
        }
        return decoded[0];
    }
}
