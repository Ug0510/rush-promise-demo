package com.rushpromise.voice;

import com.rushpromise.platform.TenantContext;
import com.rushpromise.platform.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!worker")
class VoiceTenantJobs {
    private static final Logger log = LoggerFactory.getLogger(VoiceTenantJobs.class);
    private final VoiceSessionService voice;
    private final TenantDirectory tenants;

    VoiceTenantJobs(VoiceSessionService voice, TenantDirectory tenants) {
        this.voice = voice;
        this.tenants = tenants;
    }

    @Scheduled(fixedDelayString = "${rush.voice.recovery-ms:2000}")
    void recover() { forEachShop(voice::recoverAcceptedTurns); }

    @Scheduled(fixedDelay = 60_000)
    void prune() { forEachShop(voice::pruneOldSessions); }

    private void forEachShop(Runnable work) {
        for (String shop : tenants.activeSchemas()) run(shop, work);
    }

    private void run(String schema, Runnable work) {
        try (var ignored = TenantContext.enter(schema)) {
            work.run();
        } catch (DataAccessException failure) {
            log.warn("Voice journal maintenance will retry after a database error");
        }
    }
}
