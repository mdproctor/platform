package io.casehub.platform.signing.document;

import io.casehub.platform.api.signing.document.DetachedSignature;
import io.casehub.platform.api.signing.document.DocumentSigningService;
import io.casehub.platform.api.signing.document.SignedDocument;
import io.casehub.platform.api.signing.document.SigningIdentity;

import java.util.Optional;

public class NoOpDocumentSigningService implements DocumentSigningService {
    @Override public Optional<SignedDocument> signPdf(byte[] pdfBytes, SigningIdentity identity) { return Optional.empty(); }
    @Override public Optional<DetachedSignature> signDetached(byte[] data, SigningIdentity identity) { return Optional.empty(); }
}
