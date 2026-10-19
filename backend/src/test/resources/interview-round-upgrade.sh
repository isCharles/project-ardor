#!/usr/bin/env bash
set -euo pipefail
test "${ARDOR_ACCEPTANCE_DISPOSABLE_DB:-}" = true
test -n "${RUNNER_TEMP:-}"

# This harness addresses only the isolated container created by acceptance CI.
docker exec ardor-acceptance-postgres createdb -U ardor ardor_round_upgrade
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/ardor_round_upgrade
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
  java -jar backend/target/ardor-backend-0.6.2.jar --server.port=8081 \
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
start_probe --spring.flyway.target=49 --spring.jpa.hibernate.ddl-auto=none
docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d ardor_round_upgrade \
  < backend/src/test/resources/interview-round-upgrade-fixture.sql
stop_probe
start_probe
docker exec -i ardor-acceptance-postgres psql -v ON_ERROR_STOP=1 -U ardor -d ardor_round_upgrade \
  < backend/src/test/resources/interview-round-upgrade-verify.sql
stop_probe
echo "PASS: populated V49 -> V50 upgrade preserves transcripts, reports and confirmed snapshots without invented rounds"
