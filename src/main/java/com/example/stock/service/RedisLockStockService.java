package com.example.stock.service;

import com.example.stock.domain.Stock;
import com.example.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RedisLockStockService {

    private final StockRepository stockRepository;

    /**
     * 동시성 제어는 Facade의 Redis 락이 맡으므로 synchronized를 쓰지 않는다.
     * (StockService의 synchronized를 쓰면 Redis 락 없이도 테스트가 통과해 검증이 되지 않는다)
     * 이 메서드가 반환될 때 커밋되므로, Facade가 락을 해제하기 전에 변경이 반영된다.
     */
    @Transactional
    public void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findById(id).orElseThrow();
        stock.decrease(quantity);

        stockRepository.save(stock);
    }

}
