package io.b2mash.maestro.integration.e2e.chaos;

import java.util.List;

/**
 * The six workload node roles in the chaos cluster (chaos-harness-design.md §2):
 * two instances each of the three loan-origination services. Each role has a
 * stable network alias inside the Docker network and a stable container name;
 * a role killed by the controller is replaced by a fresh container reusing the
 * same alias/service, so peers keep reaching it and log capture aggregates
 * across container generations.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable enum; safe to share.
 */
public enum NodeRole {

    LOAN_A(Service.LOAN_APPLICATION, "loan-a"),
    LOAN_B(Service.LOAN_APPLICATION, "loan-b"),
    VERIFY_A(Service.VERIFICATION_GATEWAY, "verify-a"),
    VERIFY_B(Service.VERIFICATION_GATEWAY, "verify-b"),
    UW_A(Service.UNDERWRITING, "uw-a"),
    UW_B(Service.UNDERWRITING, "uw-b");

    /** The three loan-origination services, each run as two node roles. */
    public enum Service {
        LOAN_APPLICATION("loan-application", "loan_application", 8091),
        VERIFICATION_GATEWAY("verification-gateway", "verification_gateway", 8092),
        UNDERWRITING("underwriting", "underwriting", 8093);

        private final String jarKey;
        private final String databaseName;
        private final int defaultPort;

        Service(String jarKey, String databaseName, int defaultPort) {
            this.jarKey = jarKey;
            this.databaseName = databaseName;
            this.defaultPort = defaultPort;
        }

        /** @return the {@code maestro.chaos.jar.*} key / {@code maestro.service-name}. */
        public String jarKey() {
            return jarKey;
        }

        /** @return the per-service Postgres database name on the shared instance. */
        public String databaseName() {
            return databaseName;
        }

        /** @return the in-container HTTP port the service listens on. */
        public int defaultPort() {
            return defaultPort;
        }

        /** @return the two node roles that run this service. */
        public List<NodeRole> roles() {
            return switch (this) {
                case LOAN_APPLICATION -> List.of(LOAN_A, LOAN_B);
                case VERIFICATION_GATEWAY -> List.of(VERIFY_A, VERIFY_B);
                case UNDERWRITING -> List.of(UW_A, UW_B);
            };
        }
    }

    private final Service service;
    private final String alias;

    NodeRole(Service service, String alias) {
        this.service = service;
        this.alias = alias;
    }

    /** @return the service this role runs. */
    public Service service() {
        return service;
    }

    /** @return the stable Docker network alias / container base name for this role. */
    public String alias() {
        return alias;
    }
}
