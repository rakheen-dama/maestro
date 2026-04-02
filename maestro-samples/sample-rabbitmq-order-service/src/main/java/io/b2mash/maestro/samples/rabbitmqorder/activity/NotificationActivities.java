package io.b2mash.maestro.samples.rabbitmqorder.activity;

import io.b2mash.maestro.core.annotation.Activity;

@Activity
public interface NotificationActivities {

    void notifyCustomer(String customerId, String message);
}
