package io.casehub.platform.pdf;

import io.casehub.platform.api.pdf.PdfGenerator;
import io.casehub.platform.api.pdf.PdfOptions;

import java.util.Optional;

public class NoOpPdfGenerator implements PdfGenerator {

    @Override
    public Optional<byte[]> generateFromHtml(String html, PdfOptions options) {
        return Optional.empty();
    }
}
