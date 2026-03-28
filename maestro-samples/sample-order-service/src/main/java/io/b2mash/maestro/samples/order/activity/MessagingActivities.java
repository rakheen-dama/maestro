package io.b2mash.maestro.samples.order.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.order.domain.PaymentRequest;

@Activity
public interface MessagingActivities {

    void publishPaymentRequest(PaymentRequest request);
}
