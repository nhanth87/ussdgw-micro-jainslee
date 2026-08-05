package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;
import et.restlink.ussdgw.api.AsRequest;

public record PullGrpcEvent(String target, String fullMethod, AsRequest request) implements SleeEvent {}
