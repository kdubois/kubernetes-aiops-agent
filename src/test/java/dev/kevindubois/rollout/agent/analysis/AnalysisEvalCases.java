package dev.kevindubois.rollout.agent.analysis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Loads the labelled evaluation cases from src/test/resources/analysis-eval.json
 * and combines their report sections the way DataCombinerAgent does.
 */
final class AnalysisEvalCases {

    record Expected(Boolean promote, String issueCategory, Integer minConfidence, String rootCauseContains) {
    }

    record EvalCase(String name, String diagnosticReport, String rolloutReport, String metricsReport,
            Expected expected) {
    }

    record EvalFile(String description, List<EvalCase> cases) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AnalysisEvalCases() {
    }

    static List<EvalCase> load() {
        try (InputStream in = AnalysisEvalCases.class.getResourceAsStream("/analysis-eval.json")) {
            if (in == null) {
                throw new IllegalStateException("analysis-eval.json not found on the classpath");
            }
            List<EvalCase> cases = MAPPER.readValue(in, EvalFile.class).cases();
            if (cases == null) {
                throw new IllegalStateException("analysis-eval.json contains no cases");
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse analysis-eval.json", e);
        }
    }

    static String diagnosticData(EvalCase evalCase) {
        return evalCase.diagnosticReport() + "\n\n" + evalCase.rolloutReport() + "\n\n" + evalCase.metricsReport();
    }
}
