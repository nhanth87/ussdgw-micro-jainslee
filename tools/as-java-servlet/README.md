# Demo AS — multi-level USSD on a Java servlet (HttpSession / JSESSIONID)

A three-level "My Ethio" menu behind ussdgw, MAP2MAP-aware, written as a plain Jakarta Servlet
on embedded Jetty 12. The menu position lives in the servlet `HttpSession`; ussdgw returns the
`JSESSIONID` cookie on every turn of the same USSD dialog, like the classic ussdgateway.

```bash
mvn -q package
java -jar target/as-java-servlet.jar 8090      # AS URL: http://<host>:8090/ussd/pull
```

Walk-through, wire samples, routing rule and test steps:
[`docs/as-contract/multi-level-ussd.md`](../../docs/as-contract/multi-level-ussd.md).

Demo only: in-memory balances, no authentication. Not part of the gateway build.
