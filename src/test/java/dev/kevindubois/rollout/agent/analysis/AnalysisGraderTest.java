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

    private static final String QUESTION_ID = "conclusion_supported";

    private final DecisionModel model = mock(DecisionModel.class);

    private static AnalysisResult result(int confidence) {
        return new AnalysisResult(true, confidence, "analysis", "rootCause",
                "remediation", "summary", IssueCategory.NO_ISSUES, List.of());
    }

    private void stubProbability(double probability) {
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder()
                .answers(Map.of(QUESTION_ID, YesNoAnswer.from(probability)))
                .build());
    }

    @Test
    void returnsYesProbability() {
        stubProbability(0.92);

        Optional<Double> probability = new AnalysisGrader(model, true).grade(result(90), "diagnostics");

        assertThat(probability).hasValue(0.92);
    }

    @Test
    void asksOneYesNoQuestionOverCondensedState() {
        stubProbability(0.5);

        new AnalysisGrader(model, true).grade(result(85), "diagnostics");

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        DecisionRequest request = captor.getValue();
        assertThat(request.questions()).containsKey(QUESTION_ID);
        assertThat(request.questions().get(QUESTION_ID)).isInstanceOf(YesNoQuestion.class);
        String state = ((TextInput) request.input()).text();
        assertThat(state)
                .contains("diagnostics")
                .contains("PROMOTE")
                .contains("85/100")
                .contains("summary")
                .contains("rootCause");
    }

    @Test
    void truncatesDiagnosticDataBeforeTheConclusion() {
        stubProbability(0.5);

        new AnalysisGrader(model, true).grade(result(85), "x".repeat(50_000));

        ArgumentCaptor<DecisionRequest> captor = ArgumentCaptor.forClass(DecisionRequest.class);
        org.mockito.Mockito.verify(model).decide(captor.capture());
        String state = ((TextInput) captor.getValue().input()).text();
        // diagnostic budget + conclusion, well under the decision model input limits
        assertThat(state.length()).isLessThan(4000);
        // the conclusion survives the truncation
        assertThat(state).contains("PROMOTE").contains("85/100");
    }

    @Test
    void missingAnswerFallsBackToEmpty() {
        // the model answered a different question, so ours has no answer
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder()
                .answers(Map.of("other", YesNoAnswer.from(0.5)))
                .build());

        assertThat(new AnalysisGrader(model, true).grade(result(90), "diagnostics")).isEmpty();
    }

    @Test
    void callFailureFallsBackToEmpty() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new RuntimeException("timeout"));

        assertThat(new AnalysisGrader(model, true).grade(result(90), "diagnostics")).isEmpty();
    }

    @Test
    void disabledGraderNeverCallsTheModel() {
        assertThat(new AnalysisGrader(model, false).grade(result(90), "diagnostics")).isEmpty();
        verifyNoInteractions(model);
    }
}
