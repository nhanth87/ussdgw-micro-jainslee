package et.restlink.ussdgw.admin;

/**
 * Carries the authenticated admin principal across Monitor Hub → RA hook calls
 * (RaAdminHttpRequest has no headers).
 */
public final class AdminRequestContext {
    private static final ThreadLocal<AdminAuthService.Principal> WHO = new ThreadLocal<>();

    private AdminRequestContext() {}

    public static void set(AdminAuthService.Principal who) {
        WHO.set(who);
    }

    public static AdminAuthService.Principal get() {
        return WHO.get();
    }

    public static void clear() {
        WHO.remove();
    }
}
