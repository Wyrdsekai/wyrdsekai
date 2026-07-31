package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts text from documents in a configured docs directory.
 * Supports PDF (PDFBox), DOCX/XLSX (POI), and plain text.
 * All processing is local with path traversal prevention.
 */
public class DocumentExtractorSkillExecutor implements SkillExecutor {

    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final Set<String> SUPPORTED = Set.of("pdf", "docx", "xlsx", "txt");

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final Path docsRoot;

    public DocumentExtractorSkillExecutor(String docsPath) {
        this.docsRoot = Path.of(docsPath).toAbsolutePath().normalize();

        skills.put("library.doc.extract", new SkillDefinition(
            "library.doc.extract",
            "Extract Document",
            "Extract text content from a document file",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.required("path", "string", "Relative path to document within docs directory"),
                SkillParam.optional("pages", "string", "Page range for PDFs (e.g. 1-5)")),
            SkillAuth.NONE, SkillLocality.LOCAL, false));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (!"library.doc.extract".equals(skillId)) {
            return SkillResult.unavailable(skillId);
        }

        String pathStr = params != null ? (String) params.get("path") : null;
        if (pathStr == null || pathStr.isBlank()) {
            return SkillResult.error(
                I18n.get("skill.param_required", "path"),
                0, SkillTier.NATIVE, skillId);
        }

        Path resolved = docsRoot.resolve(pathStr).normalize();
        if (!resolved.startsWith(docsRoot)) {
            return SkillResult.error(
                I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return SkillResult.error(
                I18n.get("skill.doc.failed", pathStr),
                0, SkillTier.NATIVE, skillId);
        }

        String ext = extension(resolved);
        if (!SUPPORTED.contains(ext)) {
            return SkillResult.error(
                I18n.get("skill.doc.unsupported", ext),
                0, SkillTier.NATIVE, skillId);
        }

        String pages = params.get("pages") != null ? String.valueOf(params.get("pages")) : null;
        long start = System.currentTimeMillis();

        try {
            String text = switch (ext) {
                case "pdf" -> extractPdf(resolved, pages);
                case "docx" -> extractDocx(resolved);
                case "xlsx" -> extractXlsx(resolved);
                case "txt" -> extractTxt(resolved);
                default -> "";
            };

            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("text", text);
            data.put("format", ext);
            data.put("path", pathStr);

            return SkillResult.ok(
                I18n.get("skill.doc.extracted", pathStr),
                data, elapsed, SkillTier.NATIVE, skillId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(
                I18n.get("skill.doc.failed", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private String extractPdf(Path file, String pages) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            if (pages != null && !pages.isBlank()) {
                int[] range = parsePageRange(pages, doc.getNumberOfPages());
                stripper.setStartPage(range[0]);
                stripper.setEndPage(range[1]);
            }
            return truncate(stripper.getText(doc));
        }
    }

    private String extractDocx(Path file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file.toFile()))) {
            StringBuilder sb = new StringBuilder();
            for (var para : doc.getParagraphs()) {
                sb.append(para.getText()).append("\n");
                if (sb.length() > MAX_TEXT_BYTES) break;
            }
            return truncate(sb.toString());
        }
    }

    private String extractXlsx(Path file) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new FileInputStream(file.toFile()))) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                var sheet = wb.getSheetAt(s);
                sb.append("--- ").append(sheet.getSheetName()).append(" ---\n");
                for (var row : sheet) {
                    for (var cell : row) {
                        sb.append(cell.toString()).append("\t");
                    }
                    sb.append("\n");
                    if (sb.length() > MAX_TEXT_BYTES) break;
                }
            }
            return truncate(sb.toString());
        }
    }

    private String extractTxt(Path file) throws IOException {
        return truncate(Files.readString(file));
    }

    private static int[] parsePageRange(String pages, int maxPages) {
        String[] parts = pages.split("-", 2);
        int startPage = 1;
        int endPage = maxPages;
        try {
            startPage = Math.max(1, Integer.parseInt(parts[0].trim()));
            if (parts.length > 1) {
                endPage = Math.min(maxPages, Integer.parseInt(parts[1].trim()));
            } else {
                endPage = startPage;
            }
        } catch (NumberFormatException ignored) {}
        return new int[]{startPage, endPage};
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_TEXT_BYTES ? text.substring(0, MAX_TEXT_BYTES) : text;
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}
