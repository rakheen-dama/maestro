package io.maestro.samples.order.activity;

import io.maestro.core.annotation.Activity;
import io.maestro.samples.order.domain.PaymentRequest;

@Activity
public interface MessagingActivities {

    void publishPaymentRequest(PaymentRequest request);
}
