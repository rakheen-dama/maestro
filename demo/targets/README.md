# Prometheus file-SD drop directory.
#
# demo/scripts/start-services.sh writes loan-application-b.json here when
# TWO_NODE=1 and removes it otherwise, so a single-node demo shows zero DOWN
# targets in Prometheus. Generated files are gitignored; this file keeps the
# directory present so the compose read-only mount always resolves.
