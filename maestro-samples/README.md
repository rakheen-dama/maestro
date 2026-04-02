# Maestro Samples — E-Commerce Order Fulfilment Demo

Two Spring Boot services demonstrating Maestro's durable workflow capabilities across services:

- **sample-order-service** — REST API for placing orders, runs the `OrderFulfilmentWorkflow`
- **sample-payment-gateway** — Processes payments with durable retries, runs the `PaymentProcessingWorkflow`

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

Watch the status progress: `CREATED` → `RESERVING_INVENTORY` → `REQUESTING_PAYMENT` → `AWAITING_PAYMENT` → `ARRANGING_SHIPMENT` → `COMPLETED`

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
3. Payment gateway immediately declines
4. Order workflow receives failure signal
5. **Saga compensation**: inventory reservation is automatically released

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

Prerequisites: PostgreSQL 16+, Kafka 3.x, Valkey/Redis running locally.

```bash
# Create Kafka topics (see docker-compose.yml for the full list)
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic maestro.tasks.orders --partitions 3
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic maestro.tasks.payments --partitions 3
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
| **Saga compensation** | `DECLINE_ME` triggers automatic inventory release |
| **Durable retries** | Payment gateway retries 30x with exponential backoff |
| **Signal self-recovery** | Signals persist to Postgres, delivered when workflow is ready |
| **Workflow queries** | `GET /orders/{id}/status` reads live workflow state |

---

## sample-postgres-only

**Postgres-only document approval workflow** — demonstrates Maestro with zero external dependencies beyond PostgreSQL.

- **Domain:** Submit → assign reviewer → await review signal → publish/reject → archive
- **Backend:** `maestro-store-postgres` + `maestro-messaging-postgres` + `maestro-lock-postgres`
- **No Kafka, no Valkey, no RabbitMQ**

```bash
cd maestro-samples/sample-postgres-only
docker-compose up -d
# Submit a document
curl -X POST http://localhost:8083/documents -H 'Content-Type: application/json' \
  -d '{"title": "Q4 Report", "content": "...", "authorId": "alice"}'
# Approve it
curl -X POST http://localhost:8083/documents/{id}/review -H 'Content-Type: application/json' \
  -d '{"approved": true, "reviewerId": "bob", "comments": "Looks good"}'
```

## sample-rabbitmq-order-service

**E-commerce order fulfilment using RabbitMQ** — identical workflow to `sample-order-service` but with RabbitMQ for messaging and PostgreSQL for locking.

- **Domain:** Same order fulfilment flow (reserve → pay → ship → notify)
- **Backend:** `maestro-store-postgres` + `maestro-messaging-rabbitmq` + `maestro-lock-postgres`
- **Proves workflow portability:** The `OrderFulfilmentWorkflow` class is identical — only infrastructure wiring changes.

```bash
cd maestro-samples/sample-rabbitmq-order-service
docker-compose up -d
# Place an order (same API as sample-order-service)
curl -X POST http://localhost:8084/orders -H 'Content-Type: application/json' \
  -d '{"customerId": "cust-1", "items": [{"sku": "WIDGET-1", "quantity": 2, "price": 29.99}], "paymentMethod": "card", "shippingAddress": "123 Main St"}'
```
