package com.rushpromise.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rushpromise.platform.TenantContext;
import com.rushpromise.platform.TenantDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationWorkerTest {
    private static final String FIRST_SHOP = "shop_" + "a".repeat(32);
    private static final String SECOND_SHOP = "shop_" + "b".repeat(32);
    private static final String ENROLLED = "+12025550171";

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final TenantDirectory tenants = mock(TenantDirectory.class);
    private final DeliverySettings settings = new DeliverySettings("twilio", "AC" + "0".repeat(32), "fixture-token",
            "whatsapp:+12025550172", "https://example.test", ENROLLED);
    private final NotificationWorker worker = new NotificationWorker(outbox, settings, new ObjectMapper(), tenants,
            mock(OwnerWhatsApp.class), mock(BuyerWhatsApp.class), "ready");

    @Test
    void registeredShopsAreSimulatedEvenWithLegacyTwilioEnabled() {
        when(tenants.activeSchemas()).thenReturn(List.of(FIRST_SHOP, SECOND_SHOP));
        var claimedScopes = new ArrayList<String>();
        var completedScopes = new ArrayList<String>();
        var claim = new OutboxRepository.Claim(UUID.randomUUID(), UUID.randomUUID());
        var dispatch = new OutboxRepository.Dispatch(claim.messageId(), UUID.randomUUID(), UUID.randomUUID(),
                "CONFIRMED", "fixture-recipient", "Synthetic fixture");

        when(outbox.claim(anyBoolean(), anyString())).thenAnswer(invocation -> {
            claimedScopes.add(TenantContext.schema());
            assertThat(invocation.getArgument(0, Boolean.class)).isEqualTo(TenantContext.legacy());
            assertThat(invocation.getArgument(1, String.class)).isEqualTo(TenantContext.legacy() ? ENROLLED : "");
            return TenantContext.legacy() ? null : claim;
        });
        when(outbox.begin(claim, false, "")).thenAnswer(invocation -> {
            assertThat(TenantContext.legacy()).isFalse();
            return dispatch;
        });
        when(outbox.stillCurrent(dispatch, false)).thenAnswer(invocation -> !TenantContext.legacy());
        doAnswer(invocation -> {
            completedScopes.add(TenantContext.schema());
            return null;
        }).when(outbox).observe(dispatch.attemptId(), new DeliveryResult("SIMULATED", null, null));

        worker.poll();

        assertThat(claimedScopes).containsExactly(TenantContext.LEGACY, FIRST_SHOP, SECOND_SHOP);
        assertThat(completedScopes).containsExactly(FIRST_SHOP, SECOND_SHOP);
        assertThat(TenantContext.legacy()).isTrue();
    }

    @Test
    void recoveryFailureInOneShopDoesNotSkipOtherQueuesOrLeakContext() {
        when(tenants.activeSchemas()).thenReturn(List.of(FIRST_SHOP, SECOND_SHOP));
        var recoveredScopes = new ArrayList<String>();
        doAnswer(invocation -> {
            recoveredScopes.add(TenantContext.schema());
            if (FIRST_SHOP.equals(TenantContext.schema())) throw new IllegalStateException("fixture");
            return null;
        }).when(outbox).recoverLeases();

        worker.recover();

        assertThat(recoveredScopes).containsExactly(TenantContext.LEGACY, FIRST_SHOP, SECOND_SHOP);
        assertThat(TenantContext.legacy()).isTrue();
    }
}
