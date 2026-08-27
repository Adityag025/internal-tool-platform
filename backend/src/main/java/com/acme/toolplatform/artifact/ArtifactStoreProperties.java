package com.acme.toolplatform.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration, bound from application.yml / environment variables.
 *
 * Note that no credential has a default. If ARTIFACTORY_PASSWORD is not
 * supplied the value is empty and the adapter refuses to start - which is the
 * behaviour you want. A default password is worse than no password, because it
 * works right up until it is the one in production.
 */
@ConfigurationProperties(prefix = "platform.artifacts")
public class ArtifactStoreProperties {

    public enum StoreType { FILESYSTEM, ARTIFACTORY }

    /** Which adapter to activate. */
    private StoreType store = StoreType.FILESYSTEM;

    /** Reject uploads larger than this (bytes). */
    private long maxArtifactBytes = 100L * 1024 * 1024;

    private final Filesystem filesystem = new Filesystem();
    private final Artifactory artifactory = new Artifactory();

    public static class Filesystem {
        /** Root directory that mimics the repository layout. */
        private String root = "./data/artifacts";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    public static class Artifactory {
        /** e.g. http://artifactory:8081 */
        private String baseUrl = "";
        /** The repository key, e.g. internal-tools-local */
        private String repository = "internal-tools-local";
        private String username = "";
        /** Password or, preferably, an access token. Never committed. */
        private String password = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRepository() {
            return repository;
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public long getMaxArtifactBytes() {
        return maxArtifactBytes;
    }

    public void setMaxArtifactBytes(long maxArtifactBytes) {
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public Filesystem getFilesystem() {
        return filesystem;
    }

    public Artifactory getArtifactory() {
        return artifactory;
    }
}
