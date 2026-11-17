package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the wiring the escalation tier depends on: the {@code secondopinion}
 * named model configuration in application.properties must resolve to a
 * ChatModel bean under the same name the {@code @ChatModelSupplier} of
 * {@link SecondOpinionAnalysisAgent} looks up. The tier only runs with live
 * models (see {@code EscalationSmokeTest}), so without this test a renamed
 * config block would surface as a runtime failure inside an escalation
 * instead of a build-time-visible test failure.
 */
@QuarkusTest
class SecondOpinionModelWiringTest {

    @Test
    void namedSecondOpinionModelResolves() {
        ChatModel model = Arc.container()
                .instance(ChatModel.class, ModelName.Literal.of("secondopinion"))
                .get();
        assertNotNull(model, "the secondopinion named model config must produce a ChatModel bean");
    }
}
