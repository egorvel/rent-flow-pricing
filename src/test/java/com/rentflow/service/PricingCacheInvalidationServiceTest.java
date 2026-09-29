package com.rentflow.service;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PricingCacheInvalidationServiceTest {

    @Test
    void eventCarriesOnlyTheExactSerialNumber() {
        PricingChangedEvent event = new PricingChangedEvent("drill-001");

        assertThat(PricingChangedEvent.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("serialNumber");
        assertThat(event.serialNumber()).isEqualTo("drill-001");
        assertThatNullPointerException().isThrownBy(() -> new PricingChangedEvent(null));
    }

    @Test
    void evictsOneKeyOnlyAfterCommitWithFallbackForCommittedCreate() throws Exception {
        Method method = PricingCacheInvalidationService.class.getMethod("invalidate", PricingChangedEvent.class);
        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);
        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);

        assertThat(cacheEvict).isNotNull();
        assertThat(cacheEvict.cacheNames()).containsExactly(PricingCacheService.CACHE_NAME);
        assertThat(cacheEvict.key()).isEqualTo("#event.serialNumber()");
        assertThat(cacheEvict.allEntries()).isFalse();
        assertThat(cacheEvict.beforeInvocation()).isFalse();
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
    }
}
