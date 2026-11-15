package demo.ussd;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

/**
 * Starts the demo AS on an embedded Jetty.
 *
 * <pre>
 *   java -jar target/as-java-servlet.jar [port]      (default 8090)
 *   AS URL for the ussdgw routing rule:  http://&lt;host&gt;:&lt;port&gt;/ussd/pull
 * </pre>
 */
public final class DemoAsServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;

        // SESSIONS: the servlet container creates the HttpSession and its JSESSIONID cookie.
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        // A USSD dialog lasts seconds; a session the subscriber abandoned should not live long.
        context.getSessionHandler().setMaxInactiveInterval(120);
        context.addServlet(new ServletHolder(new MyEthioMenuServlet()), "/ussd/pull");

        Server server = new Server(port);
        server.setHandler(context);
        server.start();
        System.out.println("Demo AS listening on http://0.0.0.0:" + port + "/ussd/pull");
        server.join();
    }
}
