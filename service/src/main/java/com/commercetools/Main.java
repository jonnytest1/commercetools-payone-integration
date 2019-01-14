package com.commercetools;

import com.commercetools.pspadapter.payone.*;
import com.commercetools.pspadapter.payone.config.PropertyProvider;
import com.commercetools.pspadapter.payone.config.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /**
     * It is recommended to run this service using {@code ./gradlew :service:run}
     * or {@code ./gradlew :service:runShadow} command - this will parse mandatory environment
     * variables from {@code gradle.properties} file.
     * Most of IDE should support Run/Debug configuration to run such gradle tasks.
     * <p>
     * See more in {@code Project-Lifecycle.md} documentation.
     *
     * @param args default command line args (ignored so far)
     */
    public static void main(String[] args)  {

        final PropertyProvider propertyProvider = new PropertyProvider();
        final ServiceConfig serviceConfig = new ServiceConfig(propertyProvider);

        final IntegrationService integrationService = ServiceFactory.createIntegrationService(propertyProvider, serviceConfig);
        integrationService.start();


    }
}
