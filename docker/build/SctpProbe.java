// Build-time proof that jdk.sctp is not merely present but functional.
//
// Two failure modes this catches, both of which otherwise only appear on the
// carrier host as "Protocol not supported" (plan.md §1; AGENTS.md SCTP section):
//
//   1. The module is missing entirely from the JDK.
//   2. The module is present but the native libsctp.so.1 cannot be dlopened —
//      jdk.sctp defers that load until first use, so a stripped runtime image
//      looks perfectly healthy until the first SCTP socket is opened.
//
// Reflecting into sun.nio.ch.SctpChannelImpl would prove a little more, but its
// method signatures are not API and change between JDK releases; a probe that
// breaks on a JDK upgrade is worse than the bug it guards. System.loadLibrary
// is the stable part of the contract and is exactly what jdk.sctp itself calls.
//
// Binding a socket is deliberately NOT attempted: the host kernel `sctp` module
// is a separate prerequisite that a container cannot load (docker/host-prep.sh
// does it on the host), so binding here would fail for an unrelated reason.

public class SctpProbe {
    public static void main(String[] args) {
        if (!ModuleLayer.boot().findModule("jdk.sctp").isPresent()) {
            throw new IllegalStateException(
                    "jdk.sctp module is absent — SCTP transport is mandatory for this gateway");
        }
        try {
            System.loadLibrary("sctp");
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "libsctp.so.1 could not be loaded (install libsctp1 in the image): " + t, t);
        }
        System.out.println("jdk.sctp operational: module present and native SCTP stack loadable");
    }
}