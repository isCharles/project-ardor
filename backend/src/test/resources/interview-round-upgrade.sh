#!/usr/bin/env bash
set -euo pipefail
test "${ARDOR_ACCEPTANCE_DISPOSABLE_DB:-}" = true
test -n "${RUNNER_TEMP:-}"

# Explicit acceptance profiles only; never accept an arbitrary database or source path.
case "${1:-rounds}" in
  rounds) probe_db=ardor_round_upgrade; baseline=49; fixture=interview-round ;;
  learning) probe_db=ardor_learning_origin_upgrade; baseline=50; fixture=learning-origin ;;
  attempts) probe_db=ardor_learning_attempt_upgrade; baseline=51; fixture=learning-attempts ;;
  *) echo "Unknown upgrade profile" >&2; exit 1 ;;
esac
docker exec ardor-acceptance-postgres createdb -U ardor "$probe_db"
export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$probe_db"
probe_pid=
stop_probe() {
  if test -n "$probe_pid" && test "$probe_pid" -gt 1 && kill -0 "$probe_pid" 2>/dev/null; then
    kill "$probe_pid"
    for attempt in {1..45}; do
      if ! kill -0 "$probe_pid" 2>/dev/null; then probe_pid=; return; fi
      sleep 1
    done
    echo "Upgrade probe did not exit safely" >&2
    return 1
  fi
  probe_pid=
}
trap stop_probe EXIT
start_probe() {
  java -jar backend/target/ardor-backend-0.7.0.jar --server.port=8081 \
    --spring.datasource.hikari.maximum-pool-size=2 "$@" >> "$RUNNER_TEMP/interview-round-upgrade.log" 2>&1 &
  probe_pid=$!
  for attempt in {1..90}; do
    if ! kill -0 "$probe_pid" 2>/dev/null; then tail -n 60 "$RUNNER_TEMP/interview-round-upgrade.log"; return 1; fi
    if curl --silent --fail http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"'; then return; fi
    sleep 1
  done
  echo "Upgrade probe did not become healthy" >&2
  return 1
}
start_probe "--spring.flyway.target=$baseline" --spring.jpa.hibernate.ddl-auto=none
if test "$fixture" = learning-origin; then
  docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d "$probe_db" \
    < backend/src/test/resources/interview-round-upgrade-fixture.sql
fi
docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d "$probe_db" \
  < "backend/src/test/resources/$fixture-upgrade-fixture.sql"
stop_probe
start_probe
docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d "$probe_db" \
  < "backend/src/test/resources/$fixture-upgrade-verify.sql"
# Optional read-only anomaly report assertions (currently only the V51 learning-attempt probe).
if test -f "backend/src/test/resources/$fixture-upgrade-anomaly-report.sql"; then
  docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d "$probe_db" \
    < "backend/src/test/resources/$fixture-upgrade-anomaly-report.sql"
fi
stop_probe
echo "PASS: populated V$baseline -> latest upgrade preserves the $fixture contract"
