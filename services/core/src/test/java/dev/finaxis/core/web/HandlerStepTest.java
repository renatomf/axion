package dev.finaxis.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** How {@link HandlerStep}s run around a controller method, in the Spring MVC layer only. */
@WebMvcTest(
        controllers = HandlerStepTest.TrailController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfigurer.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({HandlerStepConfiguration.class, HandlerStepTest.TrailController.class, HandlerStepTest.Steps.class})
class HandlerStepTest {

    static final String TRAIL = "trail";

    static final String ANSWER_HEADER = "X-Test-Answer";

    @RestController
    static class TrailController {

        static final AtomicInteger CALLS = new AtomicInteger();

        @GetMapping("/test/steps")
        String trail(HttpServletRequest request) {
            CALLS.incrementAndGet();
            return String.valueOf(request.getAttribute(TRAIL));
        }
    }

    // Declared out of order, so the test shows that the order comes from getOrder().
    static class Steps {

        @Bean
        HandlerStep inner() {
            return new TrailStep(20);
        }

        @Bean
        HandlerStep outer() {
            return new TrailStep(10);
        }
    }

    /** Adds its order to the trail of a wrapped request, or answers the request itself when asked. */
    record TrailStep(int order) implements HandlerStep {

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public @Nullable ModelAndView invoke(
                HttpServletRequest request, HttpServletResponse response, HandlerMethod handler, Next next)
                throws Exception {
            if (String.valueOf(order).equals(request.getHeader(ANSWER_HEADER))) {
                response.getWriter().write("answered by " + order);
                return null;
            }
            Object trail = request.getAttribute(TRAIL);
            String extended = trail == null ? String.valueOf(order) : trail + " " + order;
            return next.invoke(
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public @Nullable Object getAttribute(String name) {
                            return TRAIL.equals(name) ? extended : super.getAttribute(name);
                        }
                    },
                    response);
        }
    }

    private final MockMvcTester mvc;

    @Autowired
    HandlerStepTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void runsTheStepsByTheirOrderAndPassesOnTheRequestEachGives() {
        assertThat(mvc.get().uri("/test/steps")).hasStatusOk().hasBodyTextEqualTo("10 20");
    }

    @Test
    void aStepCanAnswerWithoutRunningTheRestOrTheController() {
        int before = TrailController.CALLS.get();

        assertThat(mvc.get().uri("/test/steps").header(ANSWER_HEADER, "10"))
                .hasStatusOk()
                .hasBodyTextEqualTo("answered by 10");
        assertThat(TrailController.CALLS).hasValue(before);
    }
}
