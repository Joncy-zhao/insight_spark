package com.insightspark.c.config;

import com.insightspark.c.service.StackCRuntimeConfigProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class StackCDynamicCorsConfiguration {

    private static final List<String> DEFAULT_ORIGINS = List.of("http://localhost:5173");

    @Bean
    public FilterRegistrationBean<CorsFilter> stackCDynamicCorsFilter(StackCRuntimeConfigProvider provider) {
        CorsConfigurationSource source = request -> buildCors(provider);
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    private CorsConfiguration buildCors(StackCRuntimeConfigProvider provider) {
        CorsConfiguration config = new CorsConfiguration();
        applyOrigins(config, provider.getStringList("security.cors.origins"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    /**
     * allowCredentials=true 时 allowedOrigins 不能含 "*"；wildcard 需改用 allowedOriginPatterns。
     */
    private void applyOrigins(CorsConfiguration config, List<String> configured) {
        List<String> origins = configured == null || configured.isEmpty() ? DEFAULT_ORIGINS : configured;
        List<String> explicit = new ArrayList<>();
        boolean wildcard = false;
        for (String raw : origins) {
            if (raw == null) {
                continue;
            }
            String origin = raw.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if ("*".equals(origin)) {
                wildcard = true;
            } else {
                explicit.add(origin);
            }
        }
        if (wildcard) {
            config.setAllowedOriginPatterns(List.of("*"));
            return;
        }
        if (!explicit.isEmpty()) {
            config.setAllowedOrigins(explicit);
            return;
        }
        config.setAllowedOrigins(DEFAULT_ORIGINS);
    }
}
