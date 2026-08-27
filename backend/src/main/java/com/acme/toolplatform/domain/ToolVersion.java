package com.acme.toolplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One immutable, published build of a tool.
 *
 * (tool_id, version) is UNIQUE at the database level. That constraint - not
 * application code - is what actually guarantees immutability: two concurrent
 * CI jobs publishing "1.2" cannot both win, whatever the service layer does.
 */
@Entity
@Table(
    name = "tool_versions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_tool_versions_tool_version",
        columnNames = {"tool_id", "version"}))
public class ToolVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tool_id", nullable = false)
    private Tool tool;

    /** The version exactly as published, e.g. "1.2". */
    @Column(nullable = false, length = 64)
    private String version;

    /** Parsed parts, stored so the DB can sort/filter numerically. */
    @Column(name = "major_part", nullable = false)
    private int majorPart;

    @Column(name = "minor_part", nullable = false)
    private int minorPart;

    @Column(name = "patch_part", nullable = false)
    private int patchPart;

    /** Coordinates inside the artifact repository, e.g. data-validator/1.2/data-validator-1.2.jar */
    @Column(name = "artifact_path", nullable = false, length = 512)
    private String artifactPath;

    /** SHA-256 of the artifact bytes - the integrity contract with clients. */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VersionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ToolVersion() {
        // required by JPA
    }

    public ToolVersion(Tool tool, SemanticVersion version, String artifactPath,
                       String checksumSha256, VersionStatus status) {
        this.tool = tool;
        this.version = version.raw();
        this.majorPart = version.major();
        this.minorPart = version.minor();
        this.patchPart = version.patch();
        this.artifactPath = artifactPath;
        this.checksumSha256 = checksumSha256;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = VersionStatus.PUBLISHED;
        }
    }

    public Long getId() {
        return id;
    }

    public Tool getTool() {
        return tool;
    }

    public String getVersion() {
        return version;
    }

    public SemanticVersion getSemanticVersion() {
        return new SemanticVersion(majorPart, minorPart, patchPart, version);
    }

    public int getMajorPart() {
        return majorPart;
    }

    public int getMinorPart() {
        return minorPart;
    }

    public int getPatchPart() {
        return patchPart;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public VersionStatus getStatus() {
        return status;
    }

    /** Status is mutable - see {@link VersionStatus} and artifact promotion. */
    public void promoteTo(VersionStatus newStatus) {
        this.status = newStatus;
    }

    /** True once bytes have been uploaded and the checksum recorded. */
    public boolean hasArtifact() {
        return checksumSha256 != null;
    }

    /**
     * Record the SHA-256 of the uploaded bytes. Write-once: after this, the
     * version's identity includes its content hash and can never change.
     */
    public void sealWith(String sha256) {
        if (this.checksumSha256 != null) {
            throw new IllegalStateException(
                    "Version " + version + " is already sealed with checksum " + checksumSha256);
        }
        this.checksumSha256 = sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
