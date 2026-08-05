# RestLink Ussdgw — ship this folder

Self-contained Quarkus fast-jar runtime for **ussdgw-jainslee**.

```
./run.sh
```

- JDK 25 only
- Config: `configs/application.properties`
- UI: `app/html/` (no jars here)
- Logs: `logs/` via `-Dussd.log.dir`
- Admin: `http://HOST:8088/admin/?key=ussd-admin`
