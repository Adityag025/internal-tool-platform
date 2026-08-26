package com.acme.toolplatform.repository;

import com.acme.toolplatform.domain.ToolVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolVersionRepository extends JpaRepository<ToolVersion, Long> {

    /** The exact-version lookup that the whole platform is built around. */
    Optional<ToolVersion> findByToolNameAndVersion(String toolName, String version);

    boolean existsByToolNameAndVersion(String toolName, String version);

    /** Newest first, ordered NUMERICALLY (1.10 sorts above 1.9). */
    List<ToolVersion> findByToolNameOrderByMajorPartDescMinorPartDescPatchPartDesc(String toolName);

    long countByToolName(String toolName);
}
