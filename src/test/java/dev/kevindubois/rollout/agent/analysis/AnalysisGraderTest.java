package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.TextInput;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisGraderTest {

    private static final String CONTRADICTION_ID = "conclusion_contradicted";
    private static final String SUFFICIENCY_ID = "evidence_sufficient";

    private final DecisionModel model = mock(DecisionModel.class);

    private static AnalysisResult result(int confidence) {
        return new AnalysisResult(true, confidence, "analysis", "rootCause",
                "remediation", "summary", IssueCategory.NO_ISSUES, List.of());
    }

    private void stubAnswers(double contradiction, double sufficiency) {
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder()
                .answers(Map.of(
                        CONTRADICTION_ID, YesNoAnswer.from(contradiction),
                        SUFFICIENCY_ID, YesNoAnswer.from(sufficiency)))
                .build());
    }

    @Test
    void contradictedConclusionLandsInTheDisputedBand() {
        stubAnswers(0.8, 0.9);

        Optional<Double> probability = new AnalysisGrader(model, true).grade(result(90), "diagnostics", "rollout-status", "metrics");

        assertThat(probability).isPresent();
        assertThat(probability.get()).isLessThan(0.30);
    }

    @Test
    void thinEvidenceLandsInTheNeutralBand() {
        // the regression this design fixes: a correct conclusion on an early-canary
        // sample used to grade as disputed and the gate held healthy rollouts
        stubAnswers(0.1, 0.2);

        Optional<Double> probability = new AnalysisGrader(model, true).grade(result(85), "diagnostics", "rollout-status", "metrics");

        assertThat(probability).isPresent();
        assertThat(probability.get()).isBetween(0.30, 0.50);
    }

    @Test
    void verifiedConclusionLandsInTheSupportedBand() {
        stubAnswers(0.1, 0.9);

        Optional<Double> probability = new AnalysisGrader(model, true).grade(result(90), "diagnostics", "rollout-status", "metrics");

        assertThat(probability).isPresent();
        assertThat(probability.get()).isGreaterThanOrEqualTo(0.50);
    }

    @Test
    void asksBothQuestionsOverCondensedState() {
        stubAnswers(0.5, 0.5);

        new AnalysisGrader(model, true).grade(result(85),
                "pod quarkus-demo-stable-1 Running\nINFO request ok\n",
                "rollout-status",
                "metrics");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        DecisionRequest request = captor.getValue();
        assertThat(request.questions()).containsKey(CONTRADICTION_ID);
        assertThat(request.questions()).containsKey(SUFFICIENCY_ID);
        assertThat(request.questions().get(CONTRADICTION_ID)).isInstanceOf(YesNoQuestion.class);
        assertThat(request.questions().get(SUFFICIENCY_ID)).isInstanceOf(YesNoQuestion.class);
        String state = ((TextInput) request.input()).text();
        assertThat(state)
                .contains("metrics")
                .contains("rollout-status")
                .contains("PROMOTE")
                .contains("85/100")
                .contains("summary")
                .contains("rootCause");
    }

    @Test
    void stateCarriesTheThresholdsAndActionSemantics() {
        stubAnswers(0.5, 0.5);

        new AnalysisGrader(model, true).grade(result(85), "diagnostics", "rollout-status", "metrics");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        String state = ((TextInput) captor.getValue().input()).text();
        assertThat(state)
                .contains("thresholds the analysis applies")
                .contains("success rate canary >= 80%")
                .contains("PROMOTE continues the rollout to its next measurement")
                .contains("ROLLBACK aborts to the stable version immediately")
                .contains("sufficient at 500+ requests in the measurement window")
                .contains("compare rates, not totals");
    }

    @Test
    void sufficiencyJudgesTheCanaryAgainstTheSampleFloor() {
        stubAnswers(0.1, 0.9);

        new AnalysisGrader(model, true, 750).grade(result(90), "diagnostics", "rollout-status", "metrics");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        DecisionRequest request = captor.getValue();
        String state = ((TextInput) request.input()).text();
        assertThat(state).contains("sufficient at 750+ requests");
        String question = ((YesNoQuestion) request.questions().get(SUFFICIENCY_ID)).text();
        assertThat(question)
                .contains("at least 750 requests")
                .contains("does not by itself make the evidence thin");
    }

    @Test
    void truncatesDiagnosticDataBeforeTheConclusion() {
        stubAnswers(0.5, 0.5);

        new AnalysisGrader(model, true).grade(result(85),
                ("ERROR something failed\n" + "quiet\n").repeat(5_000),
                "y".repeat(50_000), "z".repeat(50_000));

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        String state = ((TextInput) captor.getValue().input()).text();
        // metrics + rollout + diagnostic budgets + conclusion, under the decision model input limits
        assertThat(state.length()).isLessThan(7000);
        // the conclusion survives the truncation
        assertThat(state).contains("PROMOTE").contains("85/100");
        // the signal lines survive; the quiet lines do not
        assertThat(state).contains("ERROR something failed");
        assertThat(state).doesNotContain("quiet");
    }

    @Test
    void metricsSurviveWhenDiagnosticsOverflowTheBudget() {
        // regression: the metrics report used to be truncated away with the combined
        // diagnostic data, and the grader doubted every conclusion it could not see
        // the quantitative evidence for
        stubAnswers(0.5, 0.5);

        new AnalysisGrader(model, true).grade(result(85), "x".repeat(50_000), "y".repeat(50_000),
                "error rate: canary 0.5% vs stable 1.0%; p95 47ms vs 48ms");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        String state = ((TextInput) captor.getValue().input()).text();
        assertThat(state).contains("error rate: canary 0.5% vs stable 1.0%; p95 47ms vs 48ms");
        // every overflowing section is cut to its budget
        assertThat(state.length()).isLessThan(7000);
    }

    @Test
    void canaryEvidenceIsNotCutByPosition() {
        // regression: the log excerpt was positional and the report lists stable
        // pods before canary pods, so the canary evidence was truncated away
        stubAnswers(0.5, 0.5);

        String stableHalf = "STABLE LOGS:\n" + "INFO request ok\n".repeat(2_000);
        String canaryHalf = "CANARY LOGS:\n" + "WARN heap pressure rising in canary\n".repeat(100);
        new AnalysisGrader(model, true).grade(result(85), stableHalf + canaryHalf, "rollout", "metrics");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        String state = ((TextInput) captor.getValue().input()).text();
        // the canary signal deep in the report reaches the grader, with its section
        assertThat(state).contains("CANARY LOGS:").contains("WARN heap pressure rising in canary");
        // the quiet stable bulk does not, but its section header does
        assertThat(state).contains("STABLE LOGS:").doesNotContain("INFO request ok");
    }

    @Test
    void missingAnswerFallsBackToEmpty() {
        // the model answered only one of the two questions
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder()
                .answers(Map.of(CONTRADICTION_ID, YesNoAnswer.from(0.5)))
                .build());

        assertThat(new AnalysisGrader(model, true).grade(result(90), "diagnostics", "rollout-status", "metrics")).isEmpty();
    }

    @Test
    void callFailureFallsBackToEmpty() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new RuntimeException("timeout"));

        assertThat(new AnalysisGrader(model, true).grade(result(90), "diagnostics", "rollout-status", "metrics")).isEmpty();
    }

    @Test
    void disabledGraderNeverCallsTheModel() {
        assertThat(new AnalysisGrader(model, false).grade(result(90), "diagnostics", "rollout-status", "metrics")).isEmpty();
        verifyNoInteractions(model);
    }
}
