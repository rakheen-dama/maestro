package io.b2mash.maestro.admin.service;

import io.b2mash.maestro.admin.config.AdminProperties;
import io.b2mash.maestro.admin.model.AdminWorkflow;
import io.b2mash.maestro.admin.repository.WorkflowRepository;
import io.b2mash.maestro.core.spi.SignalMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminCommandService")
class AdminCommandServiceTest {

    @Test
    @DisplayName("every command carries a fresh message id, in the envelope and the maestro-message-id header")
    @SuppressWarnings("unchecked")
    void everyCommandCarriesAFreshMessageId() {
        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        var repository = mock(WorkflowRepository.class);
        when(repository.findByWorkflowId("wf-1")).thenReturn(Optional.of(new AdminWorkflow(UUID.randomUUID(),
                "wf-1", "OrderWorkflow", "order-service", "default", "WAITING_SIGNAL", null, Instant.now(),
                null, Instant.now(), 3)));
        var mapper = JsonMapper.builder().build();
        var service = new AdminCommandService(template, mapper, new AdminProperties(), repository);

        service.signal("wf-1", "approval", null);
        service.signal("wf-1", "approval", null);

        ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(2)).send(sent.capture());
        var ids = sent.getAllValues().stream().map(record -> {
            var envelope = mapper.readValue(record.value(), SignalMessage.class);
            var header = new String(record.headers().lastHeader(AdminCommandService.MESSAGE_ID_HEADER).value(),
                    StandardCharsets.UTF_8);
            assertThat(envelope.messageId()).isEqualTo(header).startsWith("admin:");
            assertThat(record.key()).isEqualTo("wf-1");
            return header;
        }).toList();
        assertThat(ids).doesNotHaveDuplicates()
                .as("two dashboard clicks are two signals; only a redelivery of one is suppressed");
        verify(template, times(0)).send(any(String.class), any(String.class), any(byte[].class));
    }
}
