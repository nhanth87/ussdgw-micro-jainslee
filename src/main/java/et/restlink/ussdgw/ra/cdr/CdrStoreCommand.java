package et.restlink.ussdgw.ra.cdr;

import com.microjainslee.api.OutboundCommand;
import et.restlink.ussdgw.persist.CdrEntity;

/** Commands accepted by {@code cdr-clickhouse-ra} (port 2: SBB → RA). */
public sealed interface CdrStoreCommand extends OutboundCommand {

    /** Persist one CDR milestone. Never blocks the caller. */
    record Record(CdrEntity row) implements CdrStoreCommand { }
}
