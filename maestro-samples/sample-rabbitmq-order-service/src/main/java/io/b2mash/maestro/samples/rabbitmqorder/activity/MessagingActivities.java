package io.b2mash.maestro.samples.rabbitmqorder.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.rabbitmqorder.domain.PaymentRequest;

@Activity
public interface MessagingActivities {

    void publishPaymentRequest(PaymentRequest request);
}
