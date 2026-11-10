package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemTypeAdviceTests {

    private static final URI BLANK = URI.create("about:blank");

    @Test
    void typeIsTheBaseUriFollowedByTheCodeInKebabCase() {
        var problem = problem("VALIDATION_FAILED");
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:validation-failed"));
    }

    @Test
    void baseUriIsConfigurable() {
        var properties = new ProblemTypeProperties();
        properties.setBaseUri("https://api.example.com/problems/");
        var problem = problem("RESOURCE_NOT_FOUND");
        apply(properties, problem);
        assertThat(problem.getType()).isEqualTo(URI.create("https://api.example.com/problems/resource-not-found"));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "ERROR_RESPONSE", mode = EnumSource.Mode.EXCLUDE)
    void everyCodeButErrorResponseGetsAType(ErrorCode code) {
        var problem = problem(code.name());
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isNotEqualTo(BLANK).isNotNull();
    }

    @Test
    void errorResponseKeepsAboutBlank() {
        var problem = problem("ERROR_RESPONSE");
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isIn(null, BLANK);
    }

    @Test
    void typeAlreadySetIsKept() {
        var problem = problem("INTERNAL_SERVER_ERROR");
        var own = URI.create("https://example.com/problems/out-of-stock");
        problem.setType(own);
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isEqualTo(own);
    }

    @Test
    void codeOfTheApplicationIsLeftAlone() {
        var problem = problem("OUT_OF_STOCK");
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isIn(null, BLANK);
    }

    @Test
    void noCodeIsLeftAlone() {
        var problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        apply(new ProblemTypeProperties(), problem);
        assertThat(problem.getType()).isIn(null, BLANK);
    }

    @Test
    void emptyBaseUriSendsNoType() {
        var properties = new ProblemTypeProperties();
        properties.setBaseUri("");
        var problem = problem("VALIDATION_FAILED");
        apply(properties, problem);
        assertThat(problem.getType()).isIn(null, BLANK);
    }

    private static ProblemDetail problem(String code) {
        var problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setProperty("code", code);
        return problem;
    }

    private static void apply(ProblemTypeProperties properties, ProblemDetail problem) {
        new ProblemTypeAdvice(properties).beforeBodyWrite(problem, null, null, null, null, null);
    }
}
