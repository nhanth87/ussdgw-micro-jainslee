# ussdgw-jainslee

RestLink greenfield USSD gateway (Quarkus + micro-jainslee + ra-jss7). Java 25.

## Build

```bash
mise exec -- mvn -B -ntp package -DskipTests
./build/package-dist.sh
cd dist && ./run.sh
```

Admin: `http://127.0.0.1:8088/admin/?key=ussd-admin`  
AS pull (HTTP): short-code rules → POST JSON to AS URL  
AS callback: `POST /as/callback`

See `docs/as-contract/` and `AGENTS.md`.
