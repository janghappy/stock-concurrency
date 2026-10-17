package com.example.stock.facade;

import com.example.stock.domain.Stock;
import com.example.stock.repository.StockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class LettuceLockStockFacadeTest {

    @Autowired
    private LettuceLockStockFacade lettuceLockStockFacade;

    @Autowired
    private StockRepository stockRepository;

    // deleteAll()로는 AUTO_INCREMENT가 초기화되지 않으므로 저장된 id로 조회한다
    private Long stockId;

    @BeforeEach
    public void before() {
        stockId = stockRepository.saveAndFlush(new Stock(1L, 100L)).getId();
    }

    @AfterEach
    public void after() {
        stockRepository.deleteAll();
    }

    @Test
    public void 동시에_100개의_요청() {
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);

        try {
            CompletableFuture<?>[] futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> lettuceLockStockFacade.decrease(stockId, 1L), executorService))
                    .toArray(CompletableFuture[]::new);

            // 하나라도 예외가 나면 join()에서 CompletionException으로 드러난다
            CompletableFuture.allOf(futures).orTimeout(10, TimeUnit.SECONDS).join();
        } finally {
            executorService.shutdown();
        }

        Stock stock = stockRepository.findById(stockId).orElseThrow();
        assertEquals(0L, stock.getQuantity());
    }

}