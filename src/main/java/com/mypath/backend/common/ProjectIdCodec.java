package com.mypath.backend.common;

import com.mypath.backend.exception.ResourceNotFoundException;
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
