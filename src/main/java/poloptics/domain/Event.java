package poloptics.domain;

public record Event(String eventId, long seq, String branchId, String parentEventId,
                    String kind, String eventTime, String actor,
                    String payloadJson, String fingerprint) {}
