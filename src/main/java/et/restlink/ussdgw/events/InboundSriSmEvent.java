package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;

/** Fired when inbound SRI-SM Request arrives; handled by {@code HlrResponderSbb}. */
public record InboundSriSmEvent(
        String dialogId,
        long invokeId,
        String msisdn,
        String serviceCentreAddress,
        int networkId
) implements SleeEvent {}
