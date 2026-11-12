package com.reclamos.backend.identity.m1;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1HttpConfiguration {
    @Bean("m1RestClient")
    RestClient m1RestClient(M1Settings settings) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(settings.readTimeout());
        return RestClient.builder().baseUrl(settings.baseUrl().toString()).requestFactory(requests).build();
    }
}
