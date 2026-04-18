package com.acp.cw3.service;

import com.acp.cw3.repository.DynamoRaceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;

class IngestionServiceTest {

    @Test
    void fallsBackWhenLiveIngestionFails() {
        RestClient restClient = Mockito.mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        Mockito.when(restClient.get()).thenReturn(uriSpec);
        Mockito.when(uriSpec.uri(anyString())).thenThrow(new RuntimeException("network down"));

        ProjectionService projectionService = new ProjectionService(3.0);
        PolymarketClient polymarketClient = Mockito.mock(PolymarketClient.class);
        Mockito.when(polymarketClient.fetchOddsByState()).thenThrow(new RuntimeException("network down"));
        DynamoRaceRepository repository = Mockito.mock(DynamoRaceRepository.class);
        RedisCacheService cacheService = Mockito.mock(RedisCacheService.class);

        IngestionService service = new IngestionService(
                restClient,
                projectionService,
                polymarketClient,
                repository,
                cacheService,
                "http://example.com/polls.csv",
                30,
                "",
                "https://newsapi.org/v2/everything",
                "NC,GA,ME,MI,TX",
                10,
                5
        );

        String source = service.refresh();
        assertEquals("LIVE", source);
        Mockito.verify(repository, atLeastOnce()).upsert(any());
        Mockito.verify(cacheService, times(1)).evictByPattern("states:*");
        Mockito.verify(cacheService, times(1)).evictByPattern("state:*");
        Mockito.verify(cacheService, times(1)).evictByPattern("race:*");
    }
}
