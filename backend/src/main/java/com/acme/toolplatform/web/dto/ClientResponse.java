package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.Client;
import java.time.Instant;

public record ClientResponse(Long id, String name, String description, Instant createdAt) {

    public static ClientResponse from(Client client) {
        return new ClientResponse(client.getId(), client.getName(),
                client.getDescription(), client.getCreatedAt());
    }
}
