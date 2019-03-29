package org.gluu.oxtrust.service.logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.gluu.config.oxtrust.AppConfiguration;
import org.gluu.oxtrust.ldap.service.ConfigurationService;
import org.gluu.oxtrust.model.GluuConfiguration;

/**
 * Logger service
 *
 * @author Yuriy Movchan Date: 08/19/2018
 */
@ApplicationScoped
@Named
public class LoggerService extends org.gluu.service.logger.LoggerService {

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private ConfigurationService configurationService;

    @Override
    public boolean isDisableJdkLogger() {
        return (appConfiguration.getDisableJdkLogger() != null) && appConfiguration.getDisableJdkLogger();
    }

    @Override
    public String getLoggingLevel() {
        return appConfiguration.getLoggingLevel();
    }

    @Override
    public String getExternalLoggerConfiguration() {
        GluuConfiguration configuration = configurationService.getConfiguration();
        return configuration.getOxLogConfigLocation();
    }

}
