package et.restlink.ussdgw.events;

import com.microjainslee.api.SleeEvent;
import et.restlink.ussdgw.api.AsRequest;

public record PullHttpEvent(String asUrl, AsRequest request) implements SleeEvent {}
