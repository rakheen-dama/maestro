# Maestro Samples — E-Commerce Order Fulfilment Demo

Two Spring Boot services demonstrating Maestro's durable workflow capabilities across services:

- **sample-order-service** — REST API for placing orders, runs the `OrderFulfilmentWorkflow`
- **sample-payment-gateway** — Processes payments under a retry policy (a declined card is non-retryable), runs the `PaymentProcessingWorkflow`

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Order Service (:8081)                    │
│                                                                 │
│  POST /orders ──► OrderFulfilmentWorkflow                       │
│                   │                                             │
│                   ├─ 1. Reserve inventory  (memoized activity)  │
│                   ├─ 2. Publish PaymentRequest ──────────┐      │
│                   ├─ 3. Await signal "payment.result"    │      │
│                   │      ▲                               │      │
│                   │      │  @MaestroSignalListener       │      │
│                   │      │  routes from Kafka             │      │
│                   ├─ 4. Arrange shipment                 │      │
│                   └─ 5. Notify customer                  │      │
│                                                          │      │
│  GET /orders/{id}/status ──► @QueryMethod                │      │
└──────────────────────────────────────────────────────────┼──────┘
                                                           │
                        Kafka                              │
                   ┌────────────┐                          │
                   │ payments.  │◄─────────────────────────┘
                   │ requests   │
                   └─────┬──────┘
                         │
                         ▼
┌─────���──────────────────────────────────────────────────────────┐
│                     Payment Gateway (:8082)                     │
│                                                                 │
│  @KafkaListener ──► PaymentProcessingWorkflow                   │
│                     │                                           │
│                     ├─ 1. Charge payment provider               │
│                     │     (30 retries, exponential backoff)     │
│                     │     ~30% transient failure rate            │
│                     │                                           │
│                     └─ 2. Publish PaymentResult ──┐             │
│                                                   │             │
└───���───────────────────────────────────────────────┼─────────────┘
                                                    │
                   ┌────────────┐                   │
                   │ payments.  │◄─────���────────────┘
                   │ results    │
                   └────────────┘
                         │
                         ▼
              Routed back to Order Service
              via @MaestroSignalListener
```

## Quick Start

```bash
git clone https://github.com/your-org/maestro.git
cd maestro
docker-compose up --build
```

Wait for all services to be healthy (~2-3 minutes for first build). You'll see:
```
order-service     | Started OrderServiceApplication in X.XXs
payment-gateway   | Started PaymentGatewayApplication in X.XXs
```

## Place an Order

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust-1",
    "items": [
      {"sku": "WIDGET-42", "quantity": 2, "price": 19.99},
      {"sku": "GADGET-7", "quantity": 1, "price": 49.99}
    ],
    "paymentMethod": "VISA",
    "shippingAddress": "123 Main St, Springfield"
  }' | jq .
```

Response (HTTP 202):
```json
{
  "orderId": "a1b2c3d4-..."
}
```

## Check Order Status

```bash
curl -s http://localhost:8081/orders/{orderId}/status | jq .
```

```json
{
  "orderId": "a1b2c3d4-...",
  "status": "WAITING_SIGNAL",
  "step": "AWAITING_PAYMENT",
  "failureReason": null
}
```

`status` is the workflow's durable status, read from Postgres, so any instance of
the service can answer it. `step` comes from the workflow's `@QueryMethod`, which
only the instance running the workflow can answer. It is `null` on other
instances and after the order has ended. Watch `step` progress: `RESERVING_INVENTORY`
→ `REQUESTING_PAYMENT` → `AWAITING_PAYMENT` → `ARRANGING_SHIPMENT` →
`NOTIFYING_CUSTOMER`. Once the order ships, `status` becomes `COMPLETED`.

## Simulate Payment Failure (Saga Compensation)

Use the magic payment method `DECLINE_ME` to trigger a permanent payment decline:

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust-2",
    "items": [{"sku": "WIDGET-42", "quantity": 1, "price": 19.99}],
    "paymentMethod": "DECLINE_ME",
    "shippingAddress": "456 Oak Ave"
  }' | jq .
```

Watch the logs — you'll see:
1. Inventory reserved
2. Payment request sent to Kafka
3. Payment gateway immediately declines. `PaymentDeclinedException` is declared non-retryable on the provider stub, so no retry happens. The payment workflow catches it as `ActivityExecutionException`, checks `causedBy`, publishes the decline and ends `COMPLETED`. A decline is a business outcome there, and that workflow holds nothing to undo.
4. Order workflow receives the failure signal, notifies the customer, and **throws** `OrderFailedException("Payment failed: Card permanently declined")`
5. **Saga compensation**: because the `@Saga` workflow method threw, Maestro runs `releaseReservation` with the reservation `reserve` returned
6. The order ends `FAILED`:

```bash
curl -s http://localhost:8081/orders/{orderId}/status | jq .
```
```json
{
  "orderId": "...",
  "status": "FAILED",
  "step": null,
  "failureReason": "Payment failed: Card permanently declined"
}
```

A payment result that never arrives takes the same path one hour later: the
await times out, the order throws `OrderFailedException("Payment timed out")`,
and the reservation is released.

Compensation runs only when the workflow method **throws**. A workflow that
*returns* a "failed" value is `COMPLETED` and compensates nothing. See
[docs/pitfalls.md](../docs/pitfalls.md). `OrderFulfilmentWorkflowTest` covers
the decline, the timeout and crash recovery with the in-memory test kit.

## Simulate Crash Recovery

This demonstrates Maestro's core value — workflows survive crashes:

```bash
# 1. Place an order
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust-3",
    "items": [{"sku": "WIDGET-42", "quantity": 1, "price": 19.99}],
    "paymentMethod": "VISA",
    "shippingAddress": "789 Pine Rd"
  }' | jq .

# 2. Immediately kill the payment gateway
docker-compose stop payment-gateway

# 3. The order is stuck at AWAITING_PAYMENT — the signal can't be delivered
curl -s http://localhost:8081/orders/{orderId}/status | jq .

# 4. Restart the payment gateway
docker-compose start payment-gateway

# 5. The payment workflow resumes from where it left off,
#    processes the payment, and sends the result signal.
#    The order workflow picks up the signal and completes.
curl -s http://localhost:8081/orders/{orderId}/status | jq .
```

## Running Locally (without Docker)

Prerequisites: PostgreSQL 16+ and Kafka 3.x running locally. Postgres holds the
workflow state and the distributed locks; Kafka carries the signals.

```bash
# Create Kafka topics (see docker-compose.yml for the full list)
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic maestro.signals.order-service --partitions 3
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic maestro.signals.payment-gateway --partitions 3
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic maestro.admin.events --partitions 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic payments.requests --partitions 3
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic payments.results --partitions 3

# Terminal 1: Order Service
./gradlew :maestro-samples:sample-order-service:bootRun

# Terminal 2: Payment Gateway
./gradlew :maestro-samples:sample-payment-gateway:bootRun
```

## What This Demo Shows

| Maestro Feature | Where It's Demonstrated |
|---|---|
| **Durable workflows** | Both services — workflows survive JVM restarts |
| **Activity memoization** | Replay after crash skips completed steps |
| **Cross-service signals** | Payment result flows Order → Kafka → Payment → Kafka → Order |
| **Saga compensation** | `DECLINE_ME` (or an hour without a payment result) makes the order workflow throw `OrderFailedException`; the `@Saga` releases the reservation and the order ends `FAILED` |
| **Retry policies** | Payment gateway retries a provider outage up to 30x with exponential backoff (attempt counter in memory — a crash restarts the schedule; a completed charge is never re-executed); a declined card is non-retryable |
| **Signal self-recovery** | Signals persist to Postgres, delivered when workflow is ready |
| **Workflow queries** | `GET /orders/{id}/status` combines the durable status and failure (any instance) with the live step from the `@QueryMethod` (owning instance only) |
| **Crash-recovery tests** | `OrderFulfilmentWorkflowTest` and `PaymentProcessingWorkflowTest` crash the in-memory node mid-run and recover it (`env.crashAfter`, `crashNow`, `crashBeforePersisting`) |
