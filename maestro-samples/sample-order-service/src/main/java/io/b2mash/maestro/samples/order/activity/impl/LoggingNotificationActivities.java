package io.b2mash.maestro.samples.order.activity.impl;

import io.b2mash.maestro.samples.order.activity.NotificationActivities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationActivities implements NotificationActivities {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationActivities.class);

    @Override
    public void notifyCustomer(String customerId, String message) {
        log.info("Notification to customer {}: {}", customerId, message);
    }
}
