package io.b2mash.maestro.samples.rabbitmqorder.domain;

public record ShipmentConfirmation(String shipmentId, String trackingNumber) {}
