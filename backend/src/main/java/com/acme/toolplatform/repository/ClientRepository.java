package com.acme.toolplatform.repository;

import com.acme.toolplatform.domain.Client;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByName(String name);

    boolean existsByName(String name);
}
