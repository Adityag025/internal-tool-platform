package com.acme.toolplatform.repository;

import com.acme.toolplatform.domain.Tool;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolRepository extends JpaRepository<Tool, Long> {

    Optional<Tool> findByName(String name);

    boolean existsByName(String name);
}
