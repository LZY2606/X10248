package poloptics.domain;

public record SourceBlob(String blobId, String kind, String mediaType, String unitOrDatum,
                         String originalText, String sha256, long sizeBytes,
                         String importedAt, String parseStatus, String parseDiagnostic) {}
