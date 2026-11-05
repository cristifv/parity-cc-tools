package parity.cctools.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import parity.cctools.security.CapabilityInterceptor;
import parity.cctools.security.CurrentOperator;

/** The capability check on every handler; the outermost request filter (request ids, the drain's count). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentOperator operators;

    public WebConfig(CurrentOperator operators) {
        this.operators = operators;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CapabilityInterceptor(operators)).excludePathPatterns("/css/**", "/js/**", "/vendor/**", "/img/**", "/error");
    }

    @Bean
    FilterRegistrationBean<InFlightRequests> inFlightRequestsFilter(InFlightRequests filter) {
        FilterRegistrationBean<InFlightRequests> r = new FilterRegistrationBean<>(filter);
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return r;
    }
}
