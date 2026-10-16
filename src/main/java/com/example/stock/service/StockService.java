package com.example.stock.service;

import com.example.stock.domain.Stock;
import com.example.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;

    /**
     * synchronized로 한 번에 한 스레드만 재고를 감소시킨다.
     *
     * <p>주의: {@code @Transactional}과 함께 쓰면 동시성 문제가 해결되지 않는다.
     * 트랜잭션은 Spring 프록시가 이 메서드 바깥에서 시작·커밋하므로,
     * 실행 순서가 [트랜잭션 시작 → decrease()(락 획득~해제) → 커밋]이 된다.
     * 락이 풀린 뒤 커밋 전 사이에 다른 스레드가 들어와 아직 커밋되지 않은 이전 수량을 읽으므로 감소분이 유실된다.
     * 그래서 {@code @Transactional}을 제거하고, 변경 감지 대신 saveAndFlush로 직접 반영한다.
     *
     * <p>한계: JVM 안에서만 유효한 락이라 서버(인스턴스)가 2대 이상이면 서로의 락을 모르므로 보장되지 않는다.
     */
//    @Transactional
    public synchronized void decrease(Long id, Long quantity){
        Stock stock = stockRepository.findById(id).orElseThrow();
        stock.decrease(quantity);

        stockRepository.saveAndFlush(stock);
    }

}
