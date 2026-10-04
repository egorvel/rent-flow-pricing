package com.rentflow.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private PricingRepository repository;

    @Mock
    private InventoryGateway inventoryGateway;

    @Mock
    private PricingCacheService cacheService;

    @Test
    void createsAndFlushesPricingWithTheAssignedSerialNumber() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(inventoryGateway.exists("DRILL-001")).thenReturn(true);
        when(repository.saveAndFlush(any(Pricing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        Pricing created = service.create(pricing("DRILL-001", "125.50"));

        ArgumentCaptor<Pricing> captor = ArgumentCaptor.forClass(Pricing.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(created).isSameAs(captor.getValue());
        assertThat(created.getSerialNumber()).isEqualTo("DRILL-001");
    }

    @Test
    void rejectsAnExistingSerialBeforeSaving() {
        when(repository.existsById("DRILL-001")).thenReturn(true);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.create(pricing("DRILL-001", "125.50")))
                .isInstanceOf(PricingAlreadyExistsException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(inventoryGateway, never()).exists(any());
    }

    @Test
    void rejectsASerialThatDoesNotExistInInventoryWithoutSaving() {
        when(repository.existsById("MISSING")).thenReturn(false);
        when(inventoryGateway.exists("MISSING")).thenReturn(false);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.create(pricing("MISSING", "125.50")))
                .isInstanceOf(InventoryItemNotFoundException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotSaveWhenTheInventoryLookupFails() {
        InventoryServiceUnavailableException failure = new InventoryServiceUnavailableException(
                "DRILL-001", new IllegalStateException("inventory unavailable"));
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(inventoryGateway.exists("DRILL-001")).thenThrow(failure);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.create(pricing("DRILL-001", "125.50"))).isSameAs(failure);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void translatesAConcurrentPrimaryKeyFailureToConflict() {
        when(repository.existsById("DRILL-001")).thenReturn(false);
        when(inventoryGateway.exists("DRILL-001")).thenReturn(true);
        when(repository.saveAndFlush(any(Pricing.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.create(pricing("DRILL-001", "125.50")))
                .isInstanceOf(PricingAlreadyExistsException.class);
    }

    @Test
    void returnsExistingPricing() {
        Pricing pricing = pricing("DRILL-001", "125.50");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThat(service.get("DRILL-001")).isSameAs(pricing);
        verify(repository).findById("DRILL-001");
        verifyNoInteractions(cacheService);
    }

    @Test
    void delegatesCachedRetrievalOnlyToTheSeparateCacheService() {
        PricingCacheEntry entry = PricingCacheEntry.from(pricing("DRILL-001", "125.50"));
        when(cacheService.get("DRILL-001")).thenReturn(entry);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        PricingCacheEntry result = service.getCached("DRILL-001");

        assertThat(result).isSameAs(entry);
        verify(cacheService).get("DRILL-001");
        verifyNoInteractions(repository);
    }

    @Test
    void getsCachedBatchInInputOrderThroughTheSeparateCacheService() {
        PricingCacheEntry saw = PricingCacheEntry.from(pricing("SAW-002", "75.00"));
        PricingCacheEntry drill = PricingCacheEntry.from(pricing("DRILL-001", "125.50"));
        when(cacheService.get("SAW-002")).thenReturn(saw);
        when(cacheService.get("DRILL-001")).thenReturn(drill);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        List<PricingCacheEntry> entries = service.getCachedBatch(List.of("SAW-002", "DRILL-001"));

        assertThat(entries).containsExactly(saw, drill);
        InOrder inOrder = org.mockito.Mockito.inOrder(cacheService);
        inOrder.verify(cacheService).get("SAW-002");
        inOrder.verify(cacheService).get("DRILL-001");
        verifyNoMoreInteractions(cacheService);
        verifyNoInteractions(repository);
    }

    @Test
    void stopsCachedBatchAtTheFirstMissingSerial() {
        PricingCacheEntry saw = PricingCacheEntry.from(pricing("SAW-002", "75.00"));
        when(cacheService.get("SAW-002")).thenReturn(saw);
        when(cacheService.get("MISSING")).thenThrow(new PricingNotFoundException("MISSING"));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.getCachedBatch(List.of("SAW-002", "MISSING", "LATER")))
                .isInstanceOf(PricingNotFoundException.class)
                .hasMessageContaining("MISSING");

        InOrder inOrder = org.mockito.Mockito.inOrder(cacheService);
        inOrder.verify(cacheService).get("SAW-002");
        inOrder.verify(cacheService).get("MISSING");
        verify(cacheService, never()).get("LATER");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingPricingWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.get("MISSING")).isInstanceOf(PricingNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void buildsDeterministicNonSerialSorting() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        service.list(2, 15, PricingSortField.PRICE, Sort.Direction.DESC);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(15);
        assertThat(pageable.getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("price", Sort.Direction.DESC),
                        org.assertj.core.groups.Tuple.tuple("serialNumber", Sort.Direction.ASC));
    }

    @Test
    void doesNotAddARedundantTieBreakerToUniqueSerialSorting() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        service.list(0, 20, PricingSortField.SERIAL_NUMBER, Sort.Direction.DESC);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("serialNumber", Sort.Direction.DESC));
    }

    @Test
    void replacesOnlyMutableDetailsOnExistingPricing() {
        Pricing pricing = pricing("DRILL-001", "125.50");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);
        Pricing replacement = new Pricing(
                "DRILL-001",
                new BigDecimal("150.00"),
                new BigDecimal("1.5000"),
                14,
                new BigDecimal("0.2500"),
                new BigDecimal("450.00"));

        Pricing replaced = service.replace(replacement);

        assertThat(replaced).isSameAs(pricing);
        assertThat(pricing.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(pricing.getPrice()).isEqualByComparingTo("150.00");
        assertThat(pricing.getWeekendRate()).isEqualByComparingTo("1.5000");
        assertThat(pricing.getLongRentalCondition()).isEqualTo(14);
        assertThat(pricing.getLongRentalDiscount()).isEqualByComparingTo("0.2500");
        assertThat(pricing.getDeposit()).isEqualByComparingTo("450.00");
        verify(repository).findById("DRILL-001");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsReplacementOfMissingPricingWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.replace(pricing("MISSING", "125.50")))
                .isInstanceOf(PricingNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void loadsExistingPricingBeforeDeletingIt() {
        Pricing pricing = pricing("DRILL-001", "125.50");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        service.delete("DRILL-001");

        InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).findById("DRILL-001");
        inOrder.verify(repository).delete(pricing);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void propagatesRepositoryDeletionFailure() {
        Pricing pricing = pricing("DRILL-001", "125.50");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("delete failed");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        doThrow(failure).when(repository).delete(pricing);
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.delete("DRILL-001")).isSameAs(failure);
    }

    @Test
    void rejectsDeletionOfMissingPricingWithoutMutation() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        PricingService service = new PricingService(repository, inventoryGateway, cacheService);

        assertThatThrownBy(() -> service.delete("MISSING")).isInstanceOf(PricingNotFoundException.class);

        verify(repository).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    private Pricing pricing(String serialNumber, String price) {
        return new Pricing(
                serialNumber,
                new BigDecimal(price),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }
}
