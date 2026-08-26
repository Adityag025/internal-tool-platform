package com.acme.toolplatform.repository;

import com.acme.toolplatform.domain.ClientToolConfiguration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientToolConfigurationRepository extends JpaRepository<ClientToolConfiguration, Long> {

    Optional<ClientToolConfiguration> findByClientNameAndToolName(String clientName, String toolName);

    /**
     * JOIN FETCH, deliberately.
     *
     * Without it, rendering a list of N configurations would lazily load each
     * one's tool in its own query - the classic N+1 problem, and with
     * open-in-view disabled it would not even work: the session is already
     * closed by the time the controller maps the entities.
     */
    @Query("""
           select c from ClientToolConfiguration c
             join fetch c.tool
             left join fetch c.pinnedVersion
           where c.client.name = :clientName
           """)
    List<ClientToolConfiguration> findAllByClientNameFetchingTool(@Param("clientName") String clientName);

    long deleteByClientNameAndToolName(String clientName, String toolName);
}
