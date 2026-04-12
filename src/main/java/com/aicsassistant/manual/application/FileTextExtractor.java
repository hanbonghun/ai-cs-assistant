package com.aicsassistant.manual.application;

import com.aicsassistant.common.exception.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileTextExtractor {

    public String extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();

        try {
            if (filename.endsWith(".pdf")) {
                return extractFromPdf(file.getBytes());
            } else if (filename.endsWith(".txt") || filename.endsWith(".md")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8).trim();
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                        "지원하는 파일 형식은 PDF, TXT, MD입니다.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR",
                    "파일을 읽는 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String extractFromPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_PDF",
                        "PDF에서 텍스트를 추출할 수 없습니다. 이미지 기반 스캔본은 지원되지 않습니다.");
            }
            return text.trim();
        }
    }
}
