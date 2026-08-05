# ussdgw-prod-release integration

When wiring prod packaging, point the release scripts at:

- `worktrees/ussd-service/ussdgw-jainslee/dist`, or
- `worktrees/ussd-service/RestLink/Ussdgw` after `USSD_MIRROR_LEGACY=1 ./build/package-dist.sh`

Do **not** consume classic WildFly DU artifacts from `ussdgateway/release-wildfly` for this track.

Copy this note into `ussdgw-prod-release/` when that tree is updated.
