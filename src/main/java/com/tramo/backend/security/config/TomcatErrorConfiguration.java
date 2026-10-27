package com.tramo.backend.security.config;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class TomcatErrorConfiguration {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatBadRequestHandler(
            @Lazy @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return factory -> factory.addContextCustomizers(context -> {
            var host = (StandardHost) context.getParent();
            host.setErrorReportValveClass(null);
            host.getPipeline().addValve(new ErrorReportValve() {
                @Override
                protected void report(Request request, Response response, Throwable failure) {
                    if (response.getStatus() != 400) {
                        super.report(request, response, failure);
                    } else if (response.getContentWritten() == 0 && response.setErrorReported()) {
                        resolver.resolveException(request, response, null, new RequestRejectedException("Invalid request path"));
                    }
                }
            });
        });
    }
}
