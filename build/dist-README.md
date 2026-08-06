# RestLink Ussdgw — ship this folder

Self-contained Quarkus **fast-jar** runtime for **ussdgw-jainslee** (OTA parity).

```
./run.sh
```

## Layout (not an uber-jar)

| Path | Role |
|------|------|
| `quarkus-run.jar` | Thin launcher — start with `./run.sh` |
| `ussdgw-app.jar` | Application classes at APP_HOME **root** |
| `lib/boot/` · `lib/main/` | Dependencies (replaceable jars) |
| `quarkus/` | Generated Quarkus model |
| `app/html/` | Admin UI only — **never jars here** |
| `configs/` | `application.properties` + `ss7-persist/` |
| `data/` · `logs/` | Runtime state |

Never `java -jar ussdgw-app.jar` alone. Never ship a single fat jar.

- JDK 25 only
- Admin: `http://HOST:8088/admin/?key=ussd-admin`
