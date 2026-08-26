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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One client's decision about one tool.
 *
 * Unlike a {@link ToolVersion}, this row is MUTABLE by design - and that is
 * precisely what makes rollback cheap. Moving a client from 2.0 back to 1.2 is
 * an UPDATE of this row, not a rebuild, not a redeploy of the tool, and not a
 * change to any artifact. The artifacts stay immutable; the pointer moves.
 */
@Entity
@Table(
    name = "client_tool_configuration",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ctc_client_tool",
        columnNames = {"client_id", "tool_id"}))
public class ClientToolConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tool_id", nullable = false)
    private Tool tool;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionSelector selector;

    /** Non-null exactly when selector == PINNED (enforced by a DB CHECK too). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "pinned_version_id")
    private ToolVersion pinnedVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClientToolConfiguration() {
        // required by JPA
    }

    public ClientToolConfiguration(Client client, Tool tool) {
        this.client = client;
        this.tool = tool;
    }

    /** Point this client at one exact version. */
    public void pinTo(ToolVersion version) {
        this.selector = VersionSelector.PINNED;
        this.pinnedVersion = version;
    }

    /** Opt this client in to floating on the newest published version. */
    public void followLatest() {
        this.selector = VersionSelector.LATEST;
        this.pinnedVersion = null;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Client getClient() {
        return client;
    }

    public Tool getTool() {
        return tool;
    }

    public VersionSelector getSelector() {
        return selector;
    }

    public ToolVersion getPinnedVersion() {
        return pinnedVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
