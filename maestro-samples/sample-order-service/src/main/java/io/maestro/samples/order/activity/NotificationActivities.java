package io.maestro.samples.order.activity;

import io.maestro.core.annotation.Activity;

@Activity
public interface NotificationActivities {

    void notifyCustomer(String customerId, String message);
}
